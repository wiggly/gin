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

lazy val catsEffectVersion     = "3.6.3"
lazy val munitCatsEffectVersion = "2.1.0"

lazy val root = project
  .in(file("."))
  .aggregate(core)
  .settings(
    name           := "wiggly-gin",
    publish / skip := true
  )

lazy val core = project
  .in(file("modules/core"))
  .settings(
    name := "wiggly-gin-core",
    // cats-effect's IOApp needs the main thread for correct resource cleanup.
    Compile / run / fork := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"       % catsEffectVersion,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test
    )
  )
