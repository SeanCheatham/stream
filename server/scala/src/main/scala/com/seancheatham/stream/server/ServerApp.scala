package com.seancheatham.stream.server

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.seancheatham.stream.server.api.Api
import com.typesafe.config.Config
import org.bytedeco.javacpp.Loader
import org.bytedeco.opencv.opencv_java

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

object ServerApp extends App {

  Loader.load(classOf[opencv_java])

  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val config: Config = system.settings.config

  val api = new Api(
    interface = config.getString("tm.server.api.host"),
    port = config.getInt("tm.server.api.port")
  )

  Try {
    Await.result(api.start(), Duration.Inf)

//     Tmp initial startup actions
//    val controller = StreamController(system)
//    controller.addLayer(
//      StreamLayer("l1", 1280, 720, 0, 0, 1280, 720, style = Some("mosaic.t7"), source = () => StreamLayer.FrameSource.ffmpeg("rtmp://35.211.57.140/live/c1", 1280, 720)),
//      None
//    )
//    controller.startBroadcast(
//      config.getString("tm.server.rtmp.internal") + "videdo-out-sim",
//      Some(1280),
//      Some(720),
//      Some(30)
//    )

//    system.scheduler.scheduleOnce(10.seconds)(
//      controller.removeOutput()
//    )
//
//    system.scheduler.scheduleOnce(12.seconds)(
//      controller.triggerChanges()
//    )
  }

  scala.io.StdIn.readLine("Press any key to stop.")

  Try {
    Await.result(api.stop(), Duration.Inf)
  }

  Try {
    Await.result(system.terminate(), Duration.Inf)
  }
}