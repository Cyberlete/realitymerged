import Dependencies.Libraries

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.10"

lazy val root = (project in file("."))
  .settings(
    name := "cyberApp",
    libraryDependencies ++= Seq(
//      Libraries.derevoCats,
//      Libraries.derevoCirce,
//      Libraries.circeShapes,
//      Libraries.circeFs2
    )
  )

