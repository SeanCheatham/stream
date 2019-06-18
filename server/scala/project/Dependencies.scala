import sbt._

object Dependencies {

  object versions {
    val play = "2.7.2"
  }

  val playJson =
    Seq(
      "com.typesafe.play" %% "play-json" % versions.play
    )

  val typesafe =
    Seq(
      "com.typesafe" % "config" % "1.3.4"
    )

  val test =
    Seq(
      "org.scalatest" %% "scalatest" % "3.0.1" % "test"
    )

  val logging =
    Seq(
      "ch.qos.logback" % "logback-classic" % "1.1.7",
      "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0"
    )

  val akka =
    Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.5.22",
      "com.typesafe.akka" %% "akka-stream" % "2.5.22"
    )

  val akkaHttp = {
    val version = "10.1.8"
    Seq(
      "com.typesafe.akka" %% "akka-http" % version,
      "com.typesafe.akka" %% "akka-http-testkit" % version,
      "de.heikoseeberger" %% "akka-http-play-json" % "1.25.2"
    )
  }

  val javacpp = Seq(
    "org.bytedeco" % "javacpp" % "1.5",
    "org.bytedeco" % "opencv-platform" % "4.0.1-1.5",
    "org.bytedeco" % "ffmpeg-platform" % "4.1.3-1.5"
  )

  val opencv = Seq(
    "org.bytedeco" % "javacv" % "1.5",
  )

  val dl4j = Seq (
//    "org.deeplearning4j" % "deeplearning4j-core" % "1.0.0-beta4",
//    "org.deeplearning4j" % "deeplearning4j-zoo" % "1.0.0-beta4",
//    "org.nd4j" % "nd4j-cuda-9.0" % "1.0.0-beta2"
  )

}
