import Dependencies._

ThisBuild / scalaVersion := "2.13.10"
ThisBuild / organization := "org.reality"
ThisBuild / organizationName := "reality"

ThisBuild / evictionErrorLevel := Level.Warn
ThisBuild / scalafixDependencies += Libraries.organizeImports

resolvers += Resolver.sonatypeRepo("snapshots")

def isBouncyCastleExcluded: Boolean = sys.env.getOrElse("EXCLUDE_BC", "false").toBoolean

val scalafixCommonSettings = inConfig(IntegrationTest)(scalafixConfigSettings(IntegrationTest))

bloopExportJarClassifiers in Global := Some(Set("sources"))

val ghTokenSource = TokenSource.GitConfig("github.token") || TokenSource.Environment("GITHUB_TOKEN")

githubTokenSource := ghTokenSource

lazy val commonSettings = Seq(
  scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info", "-language:reflectiveCalls"),
  scalafmtOnCompile := true,
  scalafixOnCompile := true,
  resolvers ++= List(
    Resolver.sonatypeRepo("snapshots"),
    Resolver.githubPackages("abankowski", "http-request-signer"),
    "jitpack" at "https://jitpack.io",
    "micronautics/scala on bintray" at "https://dl.bintray.com/micronautics/scala",
    "ethereum" at "https://dl.bintray.com/ethereum/maven/"
  ),
  githubTokenSource := ghTokenSource
)

lazy val commonTestSettings = Seq(
  testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % "3.0.8",

    Libraries.weaverCats,
    Libraries.weaverDiscipline,
    Libraries.weaverScalaCheck,
    Libraries.catsEffectTestkit
  ).map(_ % Test)
)

ThisBuild / assemblyMergeStrategy := {
  case "logback.xml"                                       => MergeStrategy.first
  case x if x.contains("io.netty.versions.properties")     => MergeStrategy.discard
  case PathList(xs @ _*) if xs.last == "module-info.class" => MergeStrategy.first
  case PathList("org", "bouncycastle", xs @ _*) => MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

Global / fork := true
Global / cancelable := true
Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val dockerSettings = Seq(
  Docker / packageName := "reality",
  dockerBaseImage := "openjdk:jre-alpine"
)

lazy val root = (project in file("."))
  .settings(
    name := "reality"
  )
  .aggregate(keytool, kernel, shared, core, testShared, wallet, dagShared, sdk, aci, dagL1, rosetta, tools, combined)

lazy val kernel = (project in file("modules/kernel"))
  .enablePlugins(AshScriptPlugin)
//  .dependsOn(shared, testShared % Test)
  .settings(
    name := "reality-kernel",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.semanticDB,
      Libraries.drosteCore,
      Libraries.fs2Core
    )
  )

lazy val wallet = (project in file("modules/wallet"))
  .enablePlugins(AshScriptPlugin)
  .dependsOn(keytool, shared, testShared % Test)
  .settings(
    name := "reality-wallet",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.circeFs2,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.cirisCore,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.fs2IO,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime
    )
  )

lazy val keytool = (project in file("modules/keytool"))
  .enablePlugins(AshScriptPlugin)
  .dependsOn(shared, testShared % Test)
  .settings(
    name := "reality-keytool",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      "org.bitcoinj" % "bitcoinj-core" % "0.16.2",
      "org.web3j" % "core" % "5.0.0",
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.bc,
      Libraries.bcExtensions,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.cirisCore,
      Libraries.comcast,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.fs2IO,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.newtype,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.refinedCore,
      Libraries.refinedCats
    ),
    Compile / mainClass := Some("org.reality.keytool.Main")
  )

lazy val shared = (project in file("modules/shared"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(kernel, testShared % Test)
  .settings(
    name := "reality-shared",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "org.reality",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.bc,
      Libraries.bcExtensions,
      Libraries.betterFiles,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.comcast,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.derevoScalacheck,
      Libraries.doobieQuill,
      Libraries.enumeratumCore,
      Libraries.enumeratumCirce,
      Libraries.guava,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.refinedScalacheck,
      Libraries.http4sCore
    )
  )

lazy val testShared = (project in file("modules/test-shared"))
  .configs(IntegrationTest)
  .settings(
    name := "reality-test-shared",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.catsLaws,
      Libraries.log4catsNoOp,
      Libraries.monocleLaw,
      Libraries.refinedScalacheck,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.fs2Core,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.http4sJwtAuth,
      Libraries.weaverCats,
      Libraries.weaverDiscipline,
      Libraries.weaverScalaCheck
    )
  )

lazy val sdk = (project in file("modules/sdk"))
  .dependsOn(shared % "compile->compile;test->test", testShared % Test, keytool, kernel, dagShared)
  .configs(IntegrationTest)
  .settings(
    name := "reality-sdk",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      "org.scalanlp" %% "breeze" % "1.0",
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsRetry,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.circeShapes,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.http4sCore,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.http4sJwtAuth,
      Libraries.httpSignerCore,
      Libraries.httpSignerHttp4s,
      Libraries.jawnParser,
      Libraries.jawnAst,
      Libraries.jawnFs2,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.logback,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.log4cats,
      Libraries.micrometerPrometheusRegistry,
      Libraries.shapeless
    )
  )

lazy val dagShared = (project in file("modules/dag-shared"))
  .dependsOn(shared % "compile->compile;test->test", testShared % Test, keytool % Test)
  .settings(
    name := "reality-dag-shared",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies := Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime
    )
  )

lazy val rosetta = (project in file("modules/rosetta"))
  .dependsOn(kernel, shared % "compile->compile;test->test", dagShared % "compile->compile;test->test", sdk, testShared % Test)
  .settings(
    name := "reality-rosetta",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    dockerSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.derevoCore,
      Libraries.newtype,
      Libraries.refinedCore
    )
  )

lazy val aci = (project in file("modules/aci"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(shared, kernel)
  .settings(
    name := "reality-aci",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsLaws,
      Libraries.log4cats,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCore,
      Libraries.http4sDsl,
      Libraries.http4sCirce,
      Libraries.fs2Core,
      Libraries.fs2IO,
      Libraries.mapref,
      Libraries.pureconfig,
      Libraries.sqlite,
      Libraries.logback % Runtime,
    )
  )

lazy val dagL1 = (project in file("modules/dag-l1"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(kernel, shared % "compile->compile;test->test", dagShared % "compile->compile;test->test", sdk, aci, core, testShared % Test)
  .configs(IntegrationTest)
  .settings(
    name := "reality-dag-l1",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    dockerSettings,
    libraryDependencies ++= Seq(
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeShapes,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.derevoCore,
      Libraries.doobieCore,
      Libraries.drosteCore,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.sqlite,
      Libraries.apacheDerby,
      "io.github.kawamuray.wasmtime" % "wasmtime-java" % "0.12.0"
    )
  )

lazy val tools = (project in file("modules/tools"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(core, dagL1)
  .settings(
    name := "reality-tools",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    run / connectInput := true,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.circeShapes,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.http4sDsl,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.skunkCore,
      Libraries.skunkCirce
    )
  )

lazy val core = (project in file("modules/core"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(keytool, kernel, shared % "compile->compile;test->test", testShared % Test, dagShared % "compile->compile;test->test", sdk)
  .settings(
    name := "reality-core",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    dockerSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsRetry,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.circeShapes,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.doobieCore,
      Libraries.doobieHikari,
      Libraries.doobieH2,
      Libraries.doobieQuill,
      Libraries.drosteCore,
      Libraries.drosteLaws,
      Libraries.drosteMacros,
      Libraries.fs2Core,
      Libraries.flyway,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.h2,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.http4sJwtAuth,
      Libraries.httpSignerCore,
      Libraries.httpSignerHttp4s,
      Libraries.javaxCrypto,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.newtype,
      Libraries.redis4catsEffects,
      Libraries.redis4catsLog4cats,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.skunkCore,
      Libraries.skunkCirce,
      Libraries.squants
    )
  )

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots")
)
lazy val combined = (project in file("modules/combined"))
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(kernel, shared % "compile->compile;test->test", dagShared % "compile->compile;test->test", sdk, aci, dagL1, core, testShared % Test)
  .configs(IntegrationTest)
  .settings(
    name := "reality-combined",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    dockerSettings,
    libraryDependencies ++= Seq(
      "com.chuusai" %% "shapeless" % "2.3.3",
//      "com.micronautics" %% "web3j-scala" % "4.5.17",

//      "io.monix" %% "monix-catnap" % "3.4.0",
      "co.fs2" %% "fs2-reactive-streams" % "3.10.2",
      "co.fs2" %% "fs2-io" % "3.9.3",
      Libraries.wasmtime,
      "org.web3j" % "core" % "3.3.1",
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeShapes,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.derevoCore,
      Libraries.doobieCore,
      Libraries.doobieHikari,
      Libraries.doobieQuill,
      Libraries.drosteCore,
      Libraries.flyway,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.javaIpfsHttpClient,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.sqlite
    ), Compile / mainClass := Some("org.reality.combined.Main"),
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      if (isBouncyCastleExcluded) {
        cp.filter { jar =>
          jar.data.getName.matches("bcprov-.*\\.jar") || jar.data.getName.matches("bcpkix-.*\\.jar")
        }
      } else {
        Seq.empty
      }
    }
  )

addCommandAlias("runLinter", ";scalafixAll --rules OrganizeImports")
