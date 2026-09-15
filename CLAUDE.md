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
object HealthRoutes {
  def apply[F[_]: Concurrent]: HttpRoutes[F] = {
    HttpRoutes.of[F] { case GET -> Root / "health" => Ok() }
  }
}
```

not

```scala
object HealthRoutes:
  def apply[F[_]: Concurrent]: HttpRoutes[F] =
    HttpRoutes.of[F]:
      case GET -> Root / "health" => Ok()
```

# Formatting

Formatting is scalafmt; run `sbt scalafmtAll` (or `scalafmtCheckAll` to verify without writing).

`.scalafmt.conf` pins `rewrite.scala3.convertToNewSyntax` and `rewrite.scala3.removeOptionalBraces`
to `false`. Both default to off, but they are stated explicitly so that no future scalafmt default —
and no one reaching for a rewrite rule — can quietly reformat the tree into indentation syntax
against the rule above. Do not turn them on.

# Code structure

This code uses Hexagonal/Ports & Adapters architectural pattern to separate business code from infrastructure.

`core` is the domain: pure business logic, ports, no infrastructure and no entry point. It must not
contain an `IOApp`. Effects belong at the edges.

Adapters depend on `core`, never the reverse. `server` is the driving HTTP adapter and is where the
application's `main` lives — it is the module you run.

# Testing

This code uses TDD as a development pattern.

Most tests should be unit tests that run in pure code.

Tests are [weaver](https://typelevel.org/weaver-test/) suites with `weaver-scalacheck`, and are
**property-based by default**. Do not use munit — it was removed deliberately. Reach for a worked
example (`pureTest`) when a specific case from the rules of the game is worth pinning literally, and
for a property otherwise.

Suites are objects named `*Suite`, matching the class each one extends (`SimpleIOSuite`).

The remaining test conventions — where generators live, explicit `Gen` over implicit `Arbitrary`,
and the fact that weaver does not shrink counterexamples — are in the Tests section of `README.md`.
Keep them there rather than duplicating them here.

# Deployment

The application should be designed as a 12-factor app to be built as a container.

# Build

sbt 1.13.0 on JDK 25, Scala 3.9.0.

Modules live under `modules/`. `build.sbt` defines a non-published `root` aggregator plus one
project per module, each named `wiggly-gin-<module>`: `core` is `modules/core` and `server` is
`modules/server`. Add new modules the same way, aggregate them, and give them `commonSettings` so
they inherit the test stack.

`sbt core/compile`, `sbt core/test`, `sbt test` (all modules), `sbt server/run`.

# Compiler options

Managed by sbt-tpolecat — do not set `scalacOptions` directly; use the `tpolecat*` keys.

Default mode is `DevMode` (set in `build.sbt`), so warnings do not fail local builds. Exporting
`SBT_TPOLECAT_CI=1` adds `-Werror`. Switch per-session with `sbt tpolecatCiMode` / `tpolecatDevMode`
/ `tpolecatVerboseMode`.

There is no CI pipeline yet, by choice. Until there is one, run the gate by hand before handing work
over:

```bash
SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test
```

To silence a noisy warning, exclude it rather than weakening the mode, e.g.
`Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement`.

# Working practice

Settle tooling and conventions before a lot of code lands on top of them — a formatter or a test
convention is cheap to change while there is nothing to rewrite, and expensive afterwards.

Commit per concern, not per session: each commit should be one coherent change that builds and tests
green on its own. Commit messages contain the message and nothing else — never a `Co-Authored-By`
trailer or any other generated-by footer.
