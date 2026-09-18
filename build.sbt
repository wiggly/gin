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

lazy val catsVersion       = "2.13.0"
lazy val catsEffectVersion = "3.6.3"
lazy val pureconfigVersion = "0.17.10"
lazy val circeVersion      = "0.14.16"
lazy val http4sVersion     = "0.23.37"
lazy val log4catsVersion   = "2.8.0"
lazy val logbackVersion    = "1.5.38"
lazy val scalaCheckVersion = "1.20.0"
lazy val weaverVersion     = "0.13.0"

// Tests are weaver suites: property-based by default, effectful without ceremony.
lazy val testSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel"  %% "weaver-cats"       % weaverVersion     % Test,
    "org.typelevel"  %% "weaver-scalacheck" % weaverVersion     % Test,
    "org.scalacheck" %% "scalacheck"        % scalaCheckVersion % Test
  ),
  testFrameworks += new TestFramework("weaver.framework.CatsEffect")
)

lazy val commonSettings = testSettings

lazy val root = project
  .in(file("."))
  .aggregate(core, server)
  .settings(
    name           := "wiggly-gin",
    publish / skip := true
  )

lazy val core = project
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "wiggly-gin-core",
    libraryDependencies ++= Seq(
      // The domain is pure and needs only cats-core; cats-effect is here for the ports.
      "org.typelevel" %% "cats-core"   % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion
    )
  )

// Driving adapter: exposes the game over HTTP. Depends on core, never the other way round.
// The test->test edge shares core's generators with this module's tests; see README.
lazy val server = project
  .in(file("modules/server"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "wiggly-gin-server",
    // cats-effect's IOApp needs the main thread for correct resource cleanup.
    Compile / run / fork := true,
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
