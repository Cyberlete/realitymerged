import Dependencies.Libraries

ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.10"
ThisBuild / githubOwner := "reality"
ThisBuild / githubRepository := "realitymerged"

lazy val root = (project in file("."))
  .settings(
    name := "cyberApp",
    libraryDependencies ++= Seq(
      // Core dependencies
      Libraries.catsEffect,
      Libraries.fs2Core,
      Libraries.fs2IO,

      // Circe JSON (now available)
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeFs2,

      // HTTP
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.http4sDsl,

      // CLI
      Libraries.declineCore,
      Libraries.declineEffect,

      // WASM
      Libraries.wasmtime,

      // Logging
      Libraries.log4cats,
      Libraries.logback
    ),
    Compile / mainClass := Some("main.MovementAnalysisExample"),
    // Assembly settings
    assembly / assemblyJarName := "cyberApp-assembly-0.1.0-SNAPSHOT.jar",
    assembly / mainClass := Some("main.MovementAnalysisExample"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "application.conf" => MergeStrategy.concat
      case "reference.conf" => MergeStrategy.concat
      case _ => MergeStrategy.first
    }
  )