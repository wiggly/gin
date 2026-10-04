import org.scalajs.linker.interface.ModuleKind
import org.typelevel.sbt.tpolecat.DevMode
import org.typelevel.scalacoptions.ScalacOptions

ThisBuild / organization := "wiggly"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.9.0"

// scalacOptions are managed by sbt-tpolecat; tune via tpolecat* keys, not scalacOptions.
// Local builds are lenient; CI gets fatal warnings by exporting SBT_TPOLECAT_CI=1.
ThisBuild / tpolecatDefaultOptionsMode := DevMode

// Braces, not significant indentation. Added to the dev mode options so it also
// applies in CI, verbose and release modes, which are all derived from them.
ThisBuild / tpolecatDevModeOptions += ScalacOptions.noIndent

// Coverage is off until the `coverage` command turns it on, so a plain `sbt test` stays
// uninstrumented. Main and HttpServer are the composition root and the Ember wiring: no unit test
// drives them, so counting them would only depress the figure.
// The patterns carry no `.scala`: the compiler strips the extension before matching, and the whole
// path has to match, so a pattern ending in `.scala` silently excludes nothing.
ThisBuild / coverageExcludedFiles := ".*/server/Main;.*/server/HttpServer"
// Floors sit a few points under the measured aggregate (87.83% statement, 57.14% branch) so that
// ordinary churn does not trip them. They bite on `coverageAggregate`, not on a single module.
ThisBuild / coverageFailOnMinimum      := true
ThisBuild / coverageMinimumStmtTotal   := 85
ThisBuild / coverageMinimumBranchTotal := 50

// The whole coverage run as one command. `clean` is not optional: instrumented and plain class
// files in one target break incremental compilation. `coverageOff` at the end leaves the session
// uninstrumented so that a later `test` is not silently slowed down.
addCommandAlias(
  "coverageAll",
  "clean; coverage; test; coverageAggregate; coverageOff"
)

lazy val catsVersion       = "2.13.0"
lazy val catsEffectVersion = "3.6.3"
lazy val fs2Version        = "3.14.0"
lazy val pureconfigVersion = "0.17.10"
lazy val circeVersion      = "0.14.16"
lazy val http4sVersion     = "0.23.37"
lazy val log4catsVersion   = "2.8.0"
lazy val logbackVersion    = "1.5.38"
lazy val scalaCheckVersion = "1.20.0"
lazy val weaverVersion     = "0.13.0"
lazy val tyrianVersion     = "0.14.0"
lazy val scalajsDomVersion = "2.8.0"

// Tests are weaver suites: property-based by default, effectful without ceremony.
lazy val testSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel"  %%% "weaver-cats"       % weaverVersion     % Test,
    "org.typelevel"  %%% "weaver-scalacheck" % weaverVersion     % Test,
    "org.scalacheck" %%% "scalacheck"        % scalaCheckVersion % Test
  ),
  testFrameworks += new TestFramework("weaver.framework.CatsEffect")
)

lazy val commonSettings = testSettings

lazy val root = project
  .in(file("."))
  .aggregate(core.jvm, core.js, protocol.jvm, protocol.js, server, ui)
  .settings(
    name           := "wiggly-gin",
    publish / skip := true
  )

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "wiggly-gin-core",
    libraryDependencies ++= Seq(
      // The domain is pure and needs only cats-core. cats-effect is here for the ports, and
      // fs2 for the one port that hands back a stream of a game's states.
      "org.typelevel" %%% "cats-core"   % catsVersion,
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      "co.fs2"        %%% "fs2-core"    % fs2Version
    )
  )

// The wire format. It sits outside core so that the domain keeps no JSON dependency, and outside
// server so that the browser speaks the same protocol from the same derived codec.
lazy val protocol = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/protocol"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name                               := "wiggly-gin-protocol",
    libraryDependencies += "io.circe" %%% "circe-core" % circeVersion
  )

// The browser. JavaScript only: there is no JVM meaning for a page.
lazy val ui = project
  .in(file("modules/ui"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(protocol.js % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "wiggly-gin-ui",
    // One module rather than many. Nothing here is large enough for splitting to pay, and it keeps
    // the page's script tag and the server's generated resources to one file each.
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "io.indigoengine" %%% "tyrian-io"   % tyrianVersion,
      "org.scala-js"    %%% "scalajs-dom" % scalajsDomVersion
    )
  )

// Driving adapter: exposes the game over HTTP. Depends on core, never the other way round.
// The test->test edge shares core's generators with this module's tests; see README.
lazy val server = project
  .in(file("modules/server"))
  .dependsOn(protocol.jvm % "compile->compile;test->test")
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(commonSettings)
  .settings(
    name := "wiggly-gin-server",
    // cats-effect's IOApp needs the main thread for correct resource cleanup.
    Compile / run / fork := true,
    // The image. sbt-native-packager writes the Dockerfile, the start script and the non-root
    // user, so none of the three is a file anybody has to keep in step with this build.
    Docker / packageName := "wiggly-gin",
    dockerBaseImage      := "eclipse-temurin:25-jre",
    // Matches the default in application.conf, so an image run with no environment at all listens
    // where the Dockerfile says it does.
    dockerExposedPorts := Seq(8080),
    dockerUpdateLatest := true,
    // A JVM in a container sizes its heap from the container's limit only when told to as a
    // percentage. The default is a fraction of the host's memory, which is the wrong number and is
    // wrong quietly. JAVA_OPTS still overrides this at run time.
    Universal / javaOptions += "-J-XX:MaxRAMPercentage=75",
    libraryDependencies ++= Seq(
      "org.http4s"            %% "http4s-ember-server" % http4sVersion,
      "org.http4s"            %% "http4s-circe"        % http4sVersion,
      "org.http4s"            %% "http4s-dsl"          % http4sVersion,
      "io.circe"              %% "circe-core"          % circeVersion,
      "com.github.pureconfig" %% "pureconfig-core"     % pureconfigVersion,
      "com.github.pureconfig" %% "pureconfig-ip4s"     % pureconfigVersion,
      "org.typelevel"         %% "log4cats-slf4j"      % log4catsVersion,
      "ch.qos.logback"         % "logback-classic"     % logbackVersion % Runtime
    )
  )
