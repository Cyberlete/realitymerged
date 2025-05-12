ThisBuild / scalaVersion     := "2.13.6"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "org.example"
ThisBuild / organizationName := "example"

ThisBuild / libraryDependencySchemes += "io.circe" %% "circe-core" % "early-semver"
ThisBuild / libraryDependencySchemes += "org.typelevel" %% "cats-effect" % "early-semver"
ThisBuild / evictionErrorLevel := Level.Warn

lazy val root = (project in file("."))
  .settings(
    name := "empty",
    
    // add your actual dependencies here
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.14.3",
      "io.circe" %% "circe-generic" % "0.14.3",
      "io.circe" %% "circe-parser" % "0.14.3",
      "org.reality" %% "reality-kernel" % "1.10.0-SNAPSHOT",
      "io.circe" %% "circe-refined" % "0.14.3",
      "io.circe" %% "circe-jawn" % "0.14.3",
      "com.beachape" %% "enumeratum-circe" % "1.7.2",
      "io.circe" %% "circe-magnolia-derivation" % "0.7.0",
      "org.typelevel" %% "cats-effect" % "3.4.2",
      "org.typelevel" %% "vault" % "3.3.0",
      "org.typelevel" %% "log4cats-slf4j" % "2.5.0",
      "org.tpolecat" %% "doobie-free" % "1.0.0-RC1",
      "com.monovore" %% "decline-effect" % "2.4.1",
      "co.fs2" %% "fs2-core" % "3.4.0"
    )
  )
