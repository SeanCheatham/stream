
scalacOptions ++= Seq("-unchecked", "-deprecation")

// `javacpp` are packaged with maven-plugin packaging, we need to make SBT aware that it should be added to class path.
//classpathTypes += "maven-plugin"

// javacpp `Loader` is used to determine `platform` classifier in the project`s `build.sbt`
// We define dependency here (in folder `project`) since it is used by the build itself.
//libraryDependencies += "org.bytedeco" % "javacpp" % "1.4.4"
//addSbtPlugin("org.bytedeco" % "sbt-javacpp" % "1.14")

//addSbtPlugin("org.bytedeco" % "sbt-javacv" % "1.17")
