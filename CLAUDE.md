# wiggly-gin

A multiplayer game server for the Gin Rummy game.

# Language

This game server will be written in Scala 3

It will use the Typelevel ecosystem for most libraries and effects.

# Paradigm

Code is pure functional.

It should represent business logic in pure code that does not rely on IO or concrete effects until necessary.

# Design

Make invalid states unrepresentable. Reach for a type that cannot hold a value the rules forbid,
rather than a type that can hold one and a check somewhere else that catches it.

In practice that means a private constructor and a smart constructor returning `Option`, for
anything that carries an invariant. `Meld.Set.from` and `Meld.Run.from` are the pattern. Nothing
downstream re-checks a meld it receives, because it cannot receive anything else.

Where a type cannot carry the invariant, validate once at the boundary the value enters through,
and keep every function past that point total. A guard that repeats a check the type already made
is dead code, and it is the kind of dead code that rots into a disagreement.

Some invariants span several fields, so no single type can hold them. "These piles together are
exactly one deck" is one. State those as properties in the tests instead of as assertions
scattered through the code that maintains them.

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

Comments earn their place. Default to none: name things so that the code reads as its own
explanation, and prefer extracting a well-named function over annotating a confusing one. Write a
comment only where the code cannot carry the information itself — a rule of the game the types do
not encode, a deliberate deviation, or a subtlety that would otherwise read as a mistake. Say why,
never what. A comment that restates the line below it is noise, and it drifts out of date.

# Formatting

Formatting is scalafmt; run `sbt scalafmtAll` (or `scalafmtCheckAll` to verify without writing).

`.scalafmt.conf` pins `rewrite.scala3.convertToNewSyntax` and `rewrite.scala3.removeOptionalBraces`
to `false`. Both default to off, but they are stated explicitly so that no future scalafmt default —
and no one reaching for a rewrite rule — can quietly reformat the tree into indentation syntax
against the rule above. Do not turn them on.

# Documentation

Draw diagrams as Mermaid, in a fenced block tagged `mermaid`. Do not draw them as ASCII art. This
covers anything past a trivial illustration. Simple things such as directory layout can remain ASCII.

Match the diagram type to the thing. `stateDiagram-v2` for a state machine, `flowchart` for a
directory layout or a dependency graph, `erDiagram` for a data model.

Keep a table or a list beside a diagram wherever exactness matters, and say which one is the
authority. The diagram is the overview. Do not draw the same thing twice, because two pictures of
one machine both have to be kept in step.

Mermaid renders on GitHub and in IntelliJ with the Mermaid plugin, so a broken block is invisible
until someone opens the page. Parse a block before handing the work over:

```bash
mkdir -p /tmp/mermaid-check && cd /tmp/mermaid-check && npm install mermaid jsdom
# set the jsdom globals, then: await mermaid.parse(block)
```

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
