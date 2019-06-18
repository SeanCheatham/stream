package com.seancheatham.stream.server.av

import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.stage._
import akka.{Done, NotUsed}
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacv.FrameGrabber.ImageMode
import org.bytedeco.javacv._

import scala.concurrent.{Future, Promise}

object FFmpegSource {
  def apply(path: String,
            width: Int,
            height: Int,
            framerate: Double = 25): Source[Frame, NotUsed] = Source.fromGraph(new FFmpegSource(path, width, height, framerate))
}

/**
  * A Source GraphStage which captures [[Frame]]s using FFmpeg OpenCV Bindings.
  *
  * @param path      The path to the frame source.  This can be an RTMP source (i.e. rtmp://.../live/camera) or a local video device (i.e. /dev/video0)
  * @param width     The capture width
  * @param height    The capture height
  * @param framerate The desired framerate (i.e. the maximum; frames are only grabbed as-needed by downstream demand)
  */
class FFmpegSource(path: String,
                   width: Int,
                   height: Int,
                   framerate: Double) extends GraphStage[SourceShape[Frame]] {
  val out: Outlet[Frame] = Outlet("FFmpegSource")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with OutHandler {

    var grabber: FFmpegFrameGrabber = _

    override def preStart(): Unit = {
      super.preStart()
      grabber = new FFmpegFrameGrabber(path)
      grabber.setImageWidth(width)
      grabber.setImageHeight(height)
      grabber.setImageMode(ImageMode.COLOR)
      //      grabber.setFrameRate(framerate)
      grabber.start()
    }

    override def postStop(): Unit = {
      super.postStop()
      Option(grabber).foreach(_.close())
    }


    setHandler(out, this)

    override def onPull(): Unit = {
      val frame = grabber.grab()
      if (frame == null)
        completeStage()
      else
        push(out, frame.clone())
    }
  }

  override def shape: SourceShape[Frame] = SourceShape(out)
}

/**
  * A Sink GraphStage which records [[Frame]]s using FFmpeg OpenCV Bindings.
  *
  * @param path      The path to the frame destination.  This can be an RTMP target (i.e. rtmp://.../live/camera) or a local video file (i.e. /tmp/foo.mp4)
  * @param width     The video recording width
  * @param height    The video recording height height
  * @param format    The format to use when recording, usually "flv"
  * @param framerate The desired framerate (i.e. the maximum; frames are only grabbed as-needed by downstream demand)
  */
class FFmpegSink(path: String,
                 width: Int,
                 height: Int,
                 format: String,
                 framerate: Double) extends GraphStageWithMaterializedValue[SinkShape[Frame], Future[Done]] {
  val in: Inlet[Frame] = Inlet("FFmpegSink")

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val promise = Promise[Done]()
    val logic: GraphStageLogic with InHandler =
      new GraphStageLogic(shape) with InHandler {


        var record: FFmpegFrameRecorder = _

        override def preStart(): Unit = {
          super.preStart()
          record = new FFmpegFrameRecorder(path, width, height)
          record.setFormat(format)
          record.setImageWidth(width)
          record.setImageHeight(height)
          record.setVideoCodec(avcodec.AV_CODEC_ID_FLV1)
          record.setFrameRate(framerate)
          record.start()
          pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          super.onUpstreamFinish()
          promise.trySuccess(Done)
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          super.onUpstreamFailure(ex)
          promise.tryFailure(ex)

        }

        override def postStop(): Unit = {
          super.postStop()
          Option(record).foreach { r =>
            r.close()
          }
          if (!promise.isCompleted) promise.tryFailure(new AbruptStageTerminationException(this))
        }

        setHandler(in, this)

        override def onPush(): Unit = {
          val frame =
            grab(in)
          record.record(frame)
          frame.clone()
          pull(in)
        }
      }
    (logic, promise.future)
  }

  override def shape: SinkShape[Frame] = SinkShape(in)
}

object FFmpegSink {
  def apply(path: String,
            width: Int,
            height: Int,
            format: String = "flv",
            framerate: Double = 25): Sink[Frame, Future[Done]] = Sink.fromGraph(new FFmpegSink(path, width, height, format, framerate))
}

/**
  * A Flow GraphStage which overwrites the audio in each frame to just be empty noise (a null audio source)
  *
  * @param width  The source frame's width
  * @param height The source frame's height
  */
class NullAudioFlow(width: Int, height: Int) extends GraphStage[FlowShape[Frame, Frame]] {
  val in: Inlet[Frame] = Inlet("NullAudioSink")
  val out: Outlet[Frame] = Outlet("NullAudioSource")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {
    private val pb = new ProcessBuilder(
      NullAudioFlow.ffmpeg,
      "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
      "-f", "flv", "-video_size", s"${width}x{$height}", "-i", "pipe:",
      "-shortest", "-c:v copy", "-c:a", "aac",
      "-f", "flv", "-video_size", s"${width}x{$height}", "-"
    )


    import java.io._

    private var recorder: FFmpegFrameRecorder = _
    private var grabber: FFmpegFrameGrabber = _
    private var process: Process = _
    private var grabberStarted = false

    override def preStart(): Unit = {
      super.preStart()
      process = pb.start()
      val outStream = new BufferedOutputStream(process.getOutputStream())
      recorder = new FFmpegFrameRecorder(outStream, width, height)
      recorder.setVideoCodec(avcodec.AV_CODEC_ID_FLV1)
      recorder.setFormat("flv")
      recorder.start()
    }

    override def postStop(): Unit = {
      super.postStop()
      Option(grabber).foreach(_.release())
      Option(recorder).foreach(_.release())
      Option(process).foreach(_.destroy())
    }

    override def onPush(): Unit = {
      val inputFrame = grab(in)
      recorder.record(inputFrame)
      if (!grabberStarted) startGrabber()
      // TODO: I think this part will block, so wrap in a Future on a special ExecutionContext and push result through an async handler
      push(out, grabber.grab())
    }

    override def onPull(): Unit = pull(in)

    private def startGrabber(): Unit = {
      grabber = new FFmpegFrameGrabber(process.getInputStream(), 1024)
      grabber.setFormat("flv")
      grabber.setImageMode(ImageMode.RAW)
      println("Starting Grab")
      grabber.start()
      println("Started")
      grabberStarted = true
    }

    setHandlers(in, out, this)
  }

  override def shape: FlowShape[Frame, Frame] = FlowShape(in, out)
}

object NullAudioFlow {
  val ffmpeg: String = org.bytedeco.javacpp.Loader.load(classOf[org.bytedeco.ffmpeg.ffmpeg])

}