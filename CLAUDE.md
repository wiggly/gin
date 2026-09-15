# wiggly-gin

A multiplayer game server for the Gin Rummy game.

# Language

This game server will be written in Scala 3

It will use the Typelevel ecosystem for most libraries and effects.

# Paradigm

Code is pure functional.

It should represent business logic in pure code that does not rely on IO or concrete effects until necessary.

# Code style

Use braces to delimit scope, not significant whitespace. This applies to all Scala definitions —
classes, objects, traits, methods, `if`/`for`/`while` bodies, `match` cases, and extension blocks.

```scala
object Main extends IOApp.Simple {
  val run: IO[Unit] = IO.println("wiggly-gin")
}
```

not

```scala
object Main extends IOApp.Simple:
  val run: IO[Unit] = IO.println("wiggly-gin")
```

# Code structure

This code uses Hexagonal/Ports & Adapters architectural pattern to separate business code from infrastructure.

# Testing

This code uses TDD as a development pattern.

Most tests should be unit tests that run in pure code.

# Deployment

The application should be designed as a 12-factor app to be built as a container.

# Build

sbt 1.13.0 on JDK 25, Scala 3.9.0.

Modules live under `modules/`. `build.sbt` defines a non-published `root` aggregator plus one
project per module; `core` is `modules/core`. Add new modules the same way and aggregate them.

`sbt core/compile`, `sbt core/test`, `sbt test` (all modules).

# Compiler options

Managed by sbt-tpolecat — do not set `scalacOptions` directly; use the `tpolecat*` keys.

Default mode is `DevMode` (set in `build.sbt`), so warnings do not fail local builds. CI must
export `SBT_TPOLECAT_CI=1` to add `-Werror`. Switch per-session with `sbt tpolecatCiMode` /
`tpolecatDevMode` / `tpolecatVerboseMode`.

To silence a noisy warning, exclude it rather than weakening the mode, e.g.
`Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement`.
