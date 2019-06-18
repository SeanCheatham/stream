package com.seancheatham.stream.server.api

import akka.Done
import akka.actor.{ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.event.{LogSource, Logging, LoggingAdapter}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.seancheatham.stream.server.{StreamController, StreamLayer}
import com.seancheatham.stream.server.StreamLayer.FrameSource
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport._
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class Api(interface: String = "localhost", port: Int = 8080)(implicit system: ActorSystem) extends Extension {

  private val log: LoggingAdapter =
    Logging(system, this)(new LogSource[Api] {
      override def genString(t: Api): String = s"Api($interface, $port)"
    })

  private implicit val mat: ActorMaterializer =
    ActorMaterializer()

  private implicit val ec: ExecutionContext =
    system.dispatcher

  private val controller: StreamController =
    StreamController(system)

  var binding: Option[Http.ServerBinding] = None

  val route: Route =
    pathPrefix("broadcast") {
      path("start") {
        post {
          entity(as[JsObject]) { body =>
            val target = (body \ "target").as[String]
            val width = (body \ "width").asOpt[Int]
            val height = (body \ "height").asOpt[Int]
            val fps = (body \ "fps").asOpt[Int]
            complete(
              controller
                .startBroadcast(target, width, height, fps)
                .transform {
                  case Success(o) => Success((StatusCodes.OK, o))
                  case Failure(e) => Success(StatusCodes.InternalServerError, Json.obj("error" -> e.toString))
                }
            )
          }
        }
      } ~ path("stop") {
        post {
          controller.stopBroadcast()
          complete(StatusCodes.OK)
        }
      }
    } ~
      pathPrefix("layers") {
        post {
          entity(as[JsObject]) { body =>
            val name = (body \ "name").as[String]
            val width = (body \ "width").as[Int]
            val height = (body \ "height").as[Int]
            val x = (body \ "x").asOpt[Int].getOrElse(0)
            val y = (body \ "y").asOpt[Int].getOrElse(0)
            val sourceWidth = (body \ "sourceWidth").asOpt[Int].getOrElse(width)
            val sourceHeight = (body \ "sourceHeight").asOpt[Int].getOrElse(height)
            val style = (body \ "style").asOpt[String]
            val sourceF = (body \ "type").as[String] match {
              case "ffmpeg" =>
                () => FrameSource.ffmpeg((body \ "path").as[String], sourceWidth, sourceHeight)
            }
            controller.addLayer(
              StreamLayer(name, width, height, x, y, sourceWidth, sourceHeight, sourceF, style),
              (body \ "index").asOpt[Int]
            )
            complete("Ok")
          }
        } ~
          delete {
            pathPrefix(IntNumber) { idx =>
              controller.removeLayerByIndex(idx)
              complete("Ok")
            } ~
              pathPrefix(Segment) { name =>
                controller.removeLayerByName(name)
                complete("Ok")
              }
          }
      } ~ path("info") {
      get {
        complete(controller.info())
      }
    }

  def start(): Future[Done] =
    binding.fold(
      Http().bindAndHandle(route, interface, port)
        .andThen {
          case Success(b) =>
            log.info("Successfully bound.")
            binding = Some(b)
          case Failure(exception) =>
            log.error(exception, "Failed to bind.")
        }
        .map(_ => Done)
    )(_ => Future.successful(Done))

  def stop(): Future[Done] =
    binding.fold(
      Future.successful(Done)
    )(
      _.terminate(30.seconds)
        .andThen {
          case Success(_) =>
            log.info("Successfully unbound.")
            binding = None
          case Failure(exception) =>
            log.error(exception, "Failed to unbind.")
        }
        .map(_ => Done)
    )

}

object Api extends ExtensionId[Api] with ExtensionIdProvider {
  override def createExtension(system: ExtendedActorSystem): Api = new Api()(system)

  override def lookup(): ExtensionId[_ <: Extension] = this
}
