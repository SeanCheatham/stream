name := "stream-server"
organization := "com.seancheatham"
scalaVersion := "2.12.8"

test in assembly := {}
mainClass in assembly := Some("com.seancheatham.stream.server.ServerApp")

externalResolvers := Seq(Resolver.mavenLocal) ++ externalResolvers.value.toList

libraryDependencies ++=
  Dependencies.playJson ++
    Dependencies.typesafe ++
    Dependencies.test ++
    Dependencies.logging ++
    Dependencies.javacpp ++
    Dependencies.opencv ++
    Dependencies.akka ++
    Dependencies.akkaHttp ++
    Dependencies.dl4j