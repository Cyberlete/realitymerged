import Dependencies.Libraries

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.7.3"

lazy val root = (project in file("."))
  .settings(
    name := "cyberApp",
    libraryDependencies ++= Seq(
//      Libraries.derevoCats,
//      Libraries.derevoCirce,
//      Libraries.circeShapes,
//      Libraries.circeFs2
    ),
    Compile / mainClass := Some("MovementAnalysisExample")
  )

scalacOptions -= "-Xfatal-warnings"