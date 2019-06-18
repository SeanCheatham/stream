package com.seancheatham.stream.server

import java.util.concurrent.Executors

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props}
import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.scaladsl.{Framing, Keep, Source, StreamConverters}
import akka.util.{ByteString, Timeout}
import com.seancheatham.stream.server.av.{DnnManager, FFmpegSink, FFmpegSource, MediaConversion, NullAudioFlow, Styler}
import org.bytedeco.javacv.Frame
import org.opencv.core._
import play.api.libs.json.{Format, JsObject, Json, Writes}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * The interface for controlling a live stream.  Supports the ability to add/remove outputs and layers.
  */
class StreamController(system: ActorSystem) extends Extension {

  private implicit val ec: ExecutionContext =
    system.dispatcher

  private val actor: ActorRef =
    system.actorOf(Props(new StreamControllerActor()), "stream-controller")

  /**
    * Set a [[InternalVideo]] to the current Stream.  This will cause the Stream to restart.
    */
  def setInternalVideo(output: InternalVideo): Unit =
    actor ! StreamControllerActor.Messages.SetInternalVideo(output)

  /**
    * Stop the broadcast, if it's running
    */
  def stopBroadcast(): Unit =
    actor ! StreamControllerActor.Messages.StopBroadcast

  /**
    * Add a new [[StreamLayer]] to the stream at an optional index.  This will cause the Stream to restart.
    */
  def addLayer(layer: StreamLayer, index: Option[Int]): Unit =
    actor ! StreamControllerActor.Messages.AddLayer(layer, index)

  /**
    * Remove a [[StreamLayer]] by index from Stream.  If no layer can be matched, nothing happens
    */
  def removeLayerByIndex(index: Int): Unit =
    actor ! StreamControllerActor.Messages.RemoveLayerByIndex(index)

  /**
    * Remove a [[StreamLayer]] by name from Stream.  If no layer can be matched, nothing happens
    */
  def removeLayerByName(name: String): Unit =
    actor ! StreamControllerActor.Messages.RemoveLayersByName(name)

  import akka.pattern.ask

  private implicit val timeout: Timeout =
    Timeout(5.seconds)

  def startBroadcast(target: String, width: Option[Int], height: Option[Int], fps: Option[Int]): Future[JsObject] =
    actor.ask(StreamControllerActor.Messages.StartBroadcast(target, width, height, fps))
      .mapTo[Try[StreamControllerActor.State]]
      .flatMap {
        case Success(state) => Future.successful(Json.toJson(state).as[JsObject])
        case Failure(e) => Future.failed(e)
      }

  /**
    *
    * Extract the current state of the Stream/broadcast, including layers, outputs, and live status.  This API subject to change.
    */
  def info(): Future[JsObject] =
    actor.ask(StreamControllerActor.Messages.Info)
      .mapTo[StreamControllerActor.State]
      .map(Json.toJson(_).as[JsObject])

}

object StreamController extends ExtensionId[StreamController] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): StreamController = new StreamController(system)

  override def lookup(): ExtensionId[_ <: Extension] = this
}

private class StreamControllerActor extends Actor with ActorLogging {
  private implicit val system: ActorSystem = context.system
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val mat: ActorMaterializer = ActorMaterializer()

  private implicit val _log: LoggingAdapter = log

  import StreamControllerActor.{Messages, State}

  private val internalRtmpBase: String =
    system.settings.config.getString("tm.server.rtmp.internal")

  /**
    * Not ideal, but keep a variable reference to the state such that any streams can be stopped in [[postStop()]]
    */
  private var lastState: Option[State] = None

  override def receive: Receive = {
    lastState = Some(State())
    statefulReceive(lastState.get)
  }

  override def postStop(): Unit = {
    super.postStop()
    lastState.flatMap(_.broadcastKillSwitch).foreach(_.apply())
  }

  /**
    * Terminates all active streams and restarts using the new state.
    *
    * @param state The given state to define the broadcast
    * @return an updated State with a new kill switch
    */
  private def startBroadcast(state: State): Try[State] =
    Try {
      require(state.broadcastTarget.nonEmpty)

      val target = state.broadcastTarget.get
      log.warning(s"Starting broadcast to $target")
      var framecount = 0

      val baseFrameSource =
        state.internalVideo
          .frames(state.layers)
          .recoverWithRetries(-1, {
            case e =>
              log.error(e, "InternalVidedo failed")
              state.internalVideo.frames(state.layers)
          })

      val startTime = System.currentTimeMillis()

      val frameSource: Source[Frame, NotUsed] =
        baseFrameSource
//          .async
          .extrapolate(Iterator.continually(_))
          .map { f =>
            f.timestamp = System.currentTimeMillis() - startTime
            framecount += 1
            if (framecount % state.broadcastFps == 0)
              log.info(s"Writing Frame(w=${f.imageWidth}, h=${f.imageHeight}, time=${f.timestamp}) #$framecount.  FPS: ${framecount / (f.timestamp / 1000)}")
            if (framecount % (state.broadcastFps * 2) == 0)
              f.keyFrame = true
            f
          }
          .throttle(state.broadcastFps, 1.seconds)
          .addAttributes(
            Attributes.logLevels(
              onElement = Attributes.LogLevels.Info,
              onFailure = Attributes.LogLevels.Error,
              onFinish = Attributes.LogLevels.Info
            )
          )

      val internalRtmpVideoTarget = internalRtmpBase + "video-out"

      val targetSink =
        FFmpegSink(internalRtmpVideoTarget, state.internalVideo.width, state.internalVideo.height, framerate = state.broadcastFps)

      val (killSwitch: UniqueKillSwitch, f) =
        frameSource
          .viaMat(KillSwitches.single)(Keep.right)
          .toMat(targetSink)(Keep.both)
          .run()

      f.andThen {
        case Failure(e) =>
          log.error(e, "Internal Video RTMP Sink failed for Target {}", internalRtmpVideoTarget)
      }

      log.warning(s"Writing Video to Internal RTMP {}", internalRtmpVideoTarget)

      val broadcastProcess = ffmpegMixAndBroadcast(internalRtmpVideoTarget, state.audioSource, target, state.internalVideo.width, state.internalVideo.height, state.broadcastFps)

      val killFunction = () => {
        killSwitch.shutdown()
        broadcastProcess.destroy()
      }

      state.copy(broadcastKillSwitch = Some(killFunction))
    }

  private def becomeState(updatedState: State): Unit = {
    lastState = Some(updatedState)
    context.become(
      statefulReceive(
        updatedState
      )
    )
  }

  private def statefulReceive(state: State): Receive = {
    case Messages.SetInternalVideo(internalVideo) =>
      becomeState(state.copy(internalVideo = internalVideo))

    case Messages.AddLayer(layer, index) =>
      val newLayers =
        index.fold(state.layers :+ layer)(idx => state.layers.take(idx) ++ List(layer) ++ state.layers.drop(idx))
      becomeState(
        state.copy(layers = newLayers)
      )

    case Messages.RemoveLayersByName(name) =>
      becomeState(
        state.copy(layers = state.layers.filterNot(_.name == name))
      )

    case Messages.RemoveLayerByIndex(index) =>
      becomeState(
        state.copy(
          layers = state.layers.slice(0, index) ++ state.layers.slice(index + 1, state.layers.length)
        )
      )

    case Messages.StartBroadcast(target, width, height, fps) =>
      val stateWithTarget = state.copy(
        broadcastTarget = Some(target),
        internalVideo = InternalVideo(
          width.getOrElse(state.internalVideo.width),
          height.getOrElse(state.internalVideo.height)
        ),
        broadcastFps = fps.getOrElse(state.broadcastFps)
      )
      val result = startBroadcast(stateWithTarget)
      sender() ! result
      result match {
        case Success(newState) =>
          becomeState(newState)
        case Failure(e) =>
          log.error(e, "Failed to broadcast to target {}", target)
      }

    case Messages.StopBroadcast =>
      state.broadcastKillSwitch.foreach { f =>
        f()
        becomeState(
          state.copy(broadcastKillSwitch = None, broadcastTarget = None)
        )
      }

    case Messages.Info =>
      sender() ! state
  }

  private def ffmpegMixAndBroadcast(video: String, audio: Option[String], broadcastTarget: String, width: Int, height: Int, fps: Int): Process = {

    val args = {
      val audioArgs =
        audio.fold(
          List("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100")
        )(a => List("-i", a))

      val videoArgs =
        List("-i", video)

      val mergeArgs = Nil
      //      List("-shortest", "-c:v", "copy", "-c:a", "aac")

      val outputArgs =
        List("-c:v", "libx264", "-x264-params", s"keyint=${2 * fps}:scenecut=0", "-r", s"$fps", "-f", "flv", broadcastTarget)
      List(NullAudioFlow.ffmpeg) ++ audioArgs ++ videoArgs ++ mergeArgs ++ outputArgs
    }

    val pb = new ProcessBuilder(args: _*)

    val process = pb.start()

    StreamConverters
      .fromInputStream(process.getInputStream)
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
      .runForeach(log.info("FFmpeg: {}", _))

    StreamConverters
      .fromInputStream(process.getErrorStream)
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
      .runForeach(log.error("FFmpeg: {}", _))

    process
  }

}

object StreamControllerActor {

  case class State(layers: Seq[StreamLayer] = Nil,
                   audioSource: Option[String] = None,
                   internalVideo: InternalVideo = InternalVideo(1920, 1080),
                   broadcastTarget: Option[String] = None,
                   broadcastFps: Int = 30,
                   broadcastKillSwitch: Option[() => Unit] = None)

  object State {
    implicit val writes: Writes[State] =
      Writes[State](state =>
        Json.obj(
          "layers" -> state.layers,
          "audioSource" -> state.audioSource,
          "internalVideo" -> state.internalVideo,
          "broadcastTarget" -> state.broadcastTarget,
          "broadcastFps" -> state.broadcastFps,
          "live" -> state.broadcastKillSwitch.isDefined,
        )
      )
  }

  abstract class Message

  object Messages {

    case class AddLayer(streamLayer: StreamLayer, index: Option[Int]) extends Message

    case class SetInternalVideo(internalVideo: InternalVideo) extends Message

    case class StartBroadcast(target: String, width: Option[Int], height: Option[Int], fps: Option[Int]) extends Message

    case object StopBroadcast extends Message

    case class RemoveLayersByName(name: String) extends Message

    case class RemoveLayerByIndex(index: Int) extends Message

    case object Info extends Message

  }

}

case class StreamLayer(name: String,
                       width: Int,
                       height: Int,
                       x: Int,
                       y: Int,
                       sourceWidth: Int,
                       sourceHeight: Int,
                       source: () => Source[Frame, NotUsed],
                       style: Option[String] = None) {

}

object StreamLayer {

  implicit val writes: Writes[StreamLayer] =
    Writes[StreamLayer](layer =>
      Json.obj(
        "name" -> layer.name,
        "width" -> layer.width,
        "height" -> layer.height,
        "x" -> layer.x,
        "y" -> layer.y,
        "sourceWidth" -> layer.sourceWidth,
        "sourceHeight" -> layer.sourceHeight,
        "style" -> layer.style
      )
    )

  object FrameSource {
    def ffmpeg(path: String, width: Int, height: Int): Source[Frame, NotUsed] =
      FFmpegSource(path, width, height)

    def image(path: String): Source[Frame, NotUsed] =
      Source.single(
        MediaConversion.toFrame(
          org.opencv.imgcodecs.Imgcodecs.imread(path)
        )
      ).flatMapConcat(Source.repeat)
  }

}

case class InternalVideo(width: Int, height: Int) {

  /**
    * Produces a Source of frames from the given list of layers.  Layers are stacked in-order (i.e. 0-index at the bottom/background).
    * Each layer's source produces frames, and each frame is styled/scaled/placed onto a canvas of the target size.
    */
  def frames(layers: Seq[StreamLayer])(implicit system: ActorSystem, logger: LoggingAdapter): Source[Frame, NotUsed] = {
    val maker = InternalVideoMaker(system)
    Source.zipN(
      layers
        .map(l =>
          l.source()
            .recoverWithRetries(-1, {
              case e =>
                logger.error(e, s"Layer source failed for Layer {}", l.name)
                l.source()
            })
            .filterNot(_ == null)
            .mapAsync(1)(maker.prepare(_, l))
            .extrapolate(
              Iterator.continually(_),
              Some(Styler.default.get().blank(l.width, l.height))
            )
            .map((_, l))
        )
        .toList
    )
      .mapAsync(1)(maker.mergeLayers(_, width, height))
  }
}

object InternalVideo {

  implicit val format: Format[InternalVideo] =
    Json.format[InternalVideo]
}

class InternalVideoMaker()(implicit system: ActorSystem) extends Extension {

  private implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  private var _styler: Styler = null

  private def styler: Styler = {
    if (_styler == null)
      _styler = Styler.default.get()
    _styler
  }

  def prepare(frame: Frame, layer: StreamLayer)(implicit system: ActorSystem): Future[Mat] = Future {
    val mat = MediaConversion.toMat(frame)
    styler.to4Channel(mat)
    styler.resize(mat, layer.width, layer.height)
    layer.style.foreach { s =>
      styler.to3Channel(mat)
      styler.style(mat, DnnManager(system).load(s))
      styler.to4Channel(mat)
    }
    mat
  }

  def mergeLayers(matsWithLayers: Seq[(Mat, StreamLayer)], width: Int, height: Int): Future[Frame] = Future {
    val canvas = Mat.zeros(height, width, CvType.CV_8UC3)
    matsWithLayers.foreach {
      case (mat, layer) =>
        styler.place(mat, canvas, layer.x, layer.y)
        mat.release()
    }
    val outFrame = MediaConversion.toFrame(canvas)
    outFrame
  }

}

object InternalVideoMaker extends ExtensionId[InternalVideoMaker] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): InternalVideoMaker = new InternalVideoMaker()(system)

  override def lookup(): ExtensionId[_ <: Extension] = this
}