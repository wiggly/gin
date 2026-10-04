# Web user interface implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two people play a whole match of gin rummy in a browser, against the server that already
exists.

**Architecture:** A Scala.js front end built with Tyrian, in a new `ui` module. `core` and a new
`protocol` module cross-compile to the JVM and to JavaScript, so one derived codec serves both the
server and the browser. The UI reaches the server through the public JSON API and the event stream
it already serves. The linker output is packaged into the server image.

**Tech Stack:** Scala 3.9.0, Scala.js 1.22.0, Tyrian 0.14.0, scalajs-dom 2.8.0, circe 0.14.16,
cats-effect 3.6.3, http4s 0.23.37, weaver 0.13.0, sbt 1.13.0.

**Spec:** [`docs/SPEC-WEB-UI.md`](SPEC-WEB-UI.md)

## Global Constraints

- Scala 3.9.0, sbt 1.13.0, JDK 25. Modules live under `modules/`, named `wiggly-gin-<module>`.
- Braces delimit scope. Never indentation syntax. This covers every definition, `match` case and
  `if` body.
- Tests are weaver suites, objects named `*Suite`, property-based by default with
  `weaver-scalacheck`. `pureTest` is for a rule worth pinning literally. Never munit.
- Generators stay in `modules/core/src/test/scala/wiggly/gin/gen/`. Pass an explicit `Gen` to
  `forall` rather than relying on an implicit `Arbitrary`.
- Codecs are derived. Write one by hand only where the derived shape is wrong for the API, and say
  which in a comment.
- Codecs never live in `core`.
- Comments say why, never what. Default to none.
- `scalacOptions` are managed by sbt-tpolecat. Use the `tpolecat*` keys and never set
  `scalacOptions` directly.
- Commit messages are one subject line and nothing else. No `Co-Authored-By` and no generated-by
  footer.
- Each commit builds and tests green on its own.
- The gate, before handing the work over:
  `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`

## File structure

| File | Responsibility |
| --- | --- |
| `project/plugins.sbt` | Gains sbt-scalajs and sbt-scalajs-crossproject. |
| `build.sbt` | `core` and `protocol` become cross-projects. `ui` is added. The resource generator wires the linker output into `server`. |
| `modules/protocol/src/main/scala/wiggly/gin/protocol/Codecs.scala` | The wire format, moved from `server`. |
| `modules/core/src/main/scala/wiggly/gin/core/domain/Moves.scala` | The moves available to a player, read from a view. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Model.scala` | What the browser holds. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Msg.scala` | What happened. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Api.scala` | Commands against the server's routes. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Events.scala` | The subscription over `dom.EventSource`. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Seat.scala` | Reading and writing the token in `sessionStorage`. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Update.scala` | The pure transition from a message to a model and a command. |
| `modules/ui/src/main/scala/wiggly/gin/ui/View.scala` | The screens. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Main.scala` | The Tyrian entry point. Excluded from coverage. |
| `modules/server/src/main/resources/web/index.html` | The page the linker output is loaded from. |
| `modules/server/src/main/resources/web/gin.css` | The styling. |
| `modules/server/src/main/scala/wiggly/gin/server/adapter/http/WebRoutes.scala` | Serves the page and its files. |

---

### Task 1: Cross-compile `core` and move the codecs into `protocol`

This task changes the build and moves working code. It adds no behaviour, so the existing suites
passing unchanged is the proof that the move was only a move.

**Files:**
- Modify: `project/plugins.sbt`
- Modify: `build.sbt`
- Create: `modules/protocol/src/main/scala/wiggly/gin/protocol/Codecs.scala` (moved)
- Delete: `modules/server/src/main/scala/wiggly/gin/server/adapter/http/Codecs.scala`
- Create: `modules/protocol/src/test/scala/wiggly/gin/protocol/CodecsSuite.scala` (moved)
- Delete: `modules/server/src/test/scala/wiggly/gin/server/adapter/http/CodecsSuite.scala`
- Modify: `modules/server/src/main/scala/wiggly/gin/server/adapter/http/GameRoutes.scala`

**Interfaces:**
- Consumes: nothing.
- Produces: `wiggly.gin.protocol.Codecs`, with the same members it has today. A cross-built `core`
  available as `core.js` and `core.jvm` in `build.sbt`.

- [ ] **Step 1: Add the Scala.js plugins**

Append to `project/plugins.sbt`:

```scala
addSbtPlugin("org.scala-js"       % "sbt-scalajs"                   % "1.22.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject"      % "1.4.0")
```

- [ ] **Step 2: Make the shared test settings resolve on both platforms**

`testSettings` in `build.sbt` names weaver with `%%`, which resolves the JVM artifact only. A
cross-project's JavaScript side needs `%%%`. That operator picks the artifact for the platform it
is applied to, and gives the JVM one for a JVM project. Change all three lines:

```scala
lazy val testSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel"  %%% "weaver-cats"       % weaverVersion     % Test,
    "org.typelevel"  %%% "weaver-scalacheck" % weaverVersion     % Test,
    "org.scalacheck" %%% "scalacheck"        % scalaCheckVersion % Test
  ),
  testFrameworks += new TestFramework("weaver.framework.CatsEffect")
)
```

Getting this wrong fails late and confusingly, with weaver unresolved for `core.js` only.

- [ ] **Step 3: Make `core` a cross-project and add `protocol`**

Replace the `lazy val core = project...` block with this. `CrossType.Pure` is what keeps every
source file in `modules/core/src/main/scala` exactly where it is:

```scala
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
// server so that the browser can speak the same protocol from the same derived codec.
lazy val protocol = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/protocol"))
  .dependsOn(core % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "wiggly-gin-protocol",
    libraryDependencies += "io.circe" %%% "circe-core" % circeVersion
  )
```

Note the `%%%` rather than `%%`. It picks the right artifact for each platform.

- [ ] **Step 4: Point `server` at `protocol` and update the aggregate**

In `build.sbt`, change `server`'s `dependsOn` and the `root` aggregate:

```scala
lazy val root = project
  .in(file("."))
  .aggregate(core.jvm, core.js, protocol.jvm, protocol.js, server)
  .settings(
    name           := "wiggly-gin",
    publish / skip := true
  )
```

```scala
lazy val server = project
  .in(file("modules/server"))
  .dependsOn(protocol.jvm % "compile->compile;test->test")
```

`server` reaches `core` through `protocol`, so the direct edge goes.

- [ ] **Step 5: Move the codec file**

```bash
mkdir -p modules/protocol/src/main/scala/wiggly/gin/protocol
mkdir -p modules/protocol/src/test/scala/wiggly/gin/protocol
git mv modules/server/src/main/scala/wiggly/gin/server/adapter/http/Codecs.scala \
       modules/protocol/src/main/scala/wiggly/gin/protocol/Codecs.scala
git mv modules/server/src/test/scala/wiggly/gin/server/adapter/http/CodecsSuite.scala \
       modules/protocol/src/test/scala/wiggly/gin/protocol/CodecsSuite.scala
```

Change the first line of each moved file from
`package wiggly.gin.server.adapter.http` to `package wiggly.gin.protocol`.

Nothing else in either file changes.

- [ ] **Step 6: Import the codecs where they were used**

`GameRoutes.scala` referred to `Codecs` from its own package. Add the import beside the others:

```scala
import wiggly.gin.protocol.Codecs
```

Run `grep -rn 'Codecs' modules/server/src` and add that import to every file the grep names that
does not already have it.

- [ ] **Step 7: Run the suites and make sure they pass**

Run: `sbt test`
Expected: PASS. `CodecsSuite` runs twice now, once on the JVM and once on Node, and every other
suite is unchanged. A failure here means the move changed something, which it must not.

- [ ] **Step 8: Run the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Cross-build core and move the codecs into their own module"
```

---

### Task 2: `Moves.available`

The table has to say which moves are available to a player. This is a rule of the game. It goes
in the domain, where the server and the browser both read the same one.

**Files:**
- Create: `modules/core/src/main/scala/wiggly/gin/core/domain/Moves.scala`
- Create: `modules/core/src/test/scala/wiggly/gin/core/domain/MovesSuite.scala`
- Modify: `modules/core/src/test/scala/wiggly/gin/gen/GameGen.scala`

**Interfaces:**
- Consumes: `PlayerView.InPlay`, `Move`, `Arrangement.best`, `GameState.KnockThreshold`.
- Produces: `wiggly.gin.core.domain.Moves.available(view: PlayerView.InPlay): List[Move]`.
  `GameGen.inPlay: Gen[Game.InPlay]`.

- [ ] **Step 1: Add a generator for a game that is in play**

`GameGen.gamePlayed(steps)` already returns a `Gen[Game]`, but a `Game` can be waiting or over. Add
this beside it, so a property can ask for the case it needs:

```scala
  /** A game with a round in play. The filter is the whole of it: a game that is waiting or over
    * has no round for a move to be offered in.
    */
  val inPlay: Gen[Game.InPlay] = gamePlayed(12).collect { case playing: Game.InPlay => playing }
```

- [ ] **Step 2: Write the failing agreement property**

Create `modules/core/src/test/scala/wiggly/gin/core/domain/MovesSuite.scala`:

```scala
package wiggly.gin.core.domain

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.gen.GameGen

object MovesSuite extends SimpleIOSuite with Checkers {

  test("every move offered is accepted by the rules") {
    forall(GameGen.inPlay) { game =>
      val onTurn = game.round.onTurn
      val view   = viewOf(game, onTurn)

      val refused = Moves.available(view).filter { move =>
        GameState(game.round, onTurn, move).isLeft
      }

      expect(refused.isEmpty)
    }
  }

  test("every move left out is refused by the rules") {
    forall(GameGen.inPlay) { game =>
      val onTurn   = game.round.onTurn
      val view     = viewOf(game, onTurn)
      val offered  = Moves.available(view).toSet
      val accepted = GameGen.legalMoves(game.round).map(_._1).toSet

      expect.same(accepted, offered)
    }
  }

  test("a player off turn is offered nothing") {
    forall(GameGen.inPlay) { game =>
      expect(Moves.available(viewOf(game, game.round.onTurn.other)).isEmpty)
    }
  }

  private def viewOf(game: Game.InPlay, you: Player): PlayerView.InPlay = {
    PlayerView.of(game, you) match {
      case view: PlayerView.InPlay => view
      case other                   => sys.error(s"expected a round in play, got $other")
    }
  }
}
```

The second property is the one that matters. It pins the function against the transition function
itself rather than against a second reading of the rules.

- [ ] **Step 3: Run it and make sure it fails**

Run: `sbt "core/testOnly wiggly.gin.core.domain.MovesSuite"`
Expected: FAIL to compile, with "Not found: Moves".

- [ ] **Step 4: Write the implementation**

Create `modules/core/src/main/scala/wiggly/gin/core/domain/Moves.scala`:

```scala
package wiggly.gin.core.domain

/** What a player may do, answered from what that player can see.
  *
  * This is the rule the client and the server have to agree on, so it is stated here once rather
  * than a second time in a browser. A redacted view carries enough to settle it: nothing a player
  * is not shown bears on which of their own moves is legal.
  */
object Moves {

  def available(view: PlayerView.InPlay): List[Move] = {
    if (view.you =!= view.onTurn) {
      Nil
    } else {
      view.phase match {
        case Phase.UpcardOffered(_)           => List(Move.DrawDiscard, Move.Pass)
        case Phase.AwaitingOpeningDraw        => List(Move.DrawStock)
        case Phase.AwaitingDraw(_)            => List(Move.DrawStock, Move.DrawDiscard)
        case Phase.AwaitingDiscard(_, taken)  => discards(view.arrangement, taken)
      }
    }
  }

  private def discards(held: Arrangement, taken: Option[Card]): List[Move] = {
    val cards = held.melds.flatMap(_.cards.toList) ++ held.deadwood

    cards.sorted.filterNot(taken.contains).flatMap { card =>
      val kept = remove(cards, card)
      val knock =
        Option.when(Arrangement.best(kept).deadwoodValue <= GameState.KnockThreshold)(
          Move.Knock(card)
        )

      Move.Discard(card) :: knock.toList
    }
  }

  /** One copy, not every copy. A hand holds a card once, but taking every copy out would be a
    * different rule from the one the round applies, and the two must not drift apart.
    */
  private def remove(cards: List[Card], card: Card): List[Card] = {
    cards.span(_ =!= card) match {
      case (before, _ :: after) => before ++ after
      case (all, Nil)           => all
    }
  }
}
```

If `=!=` does not resolve, add `import cats.syntax.all.*` at the top.

- [ ] **Step 5: Run the suite and make sure it passes**

Run: `sbt "core/testOnly wiggly.gin.core.domain.MovesSuite"`
Expected: PASS, all three properties.

If "every move left out is refused" fails, read the counterexample before changing the
implementation. The generator reports the exact game, and the disagreement names the rule one side
has wrong. Do not weaken the property to make it pass.

- [ ] **Step 6: Run the whole suite**

Run: `sbt test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Answer which moves a player may make from their own view"
```

---

### Task 3: The `ui` module, its model and its update function

The build gains a JavaScript-only module, and the pure half of the UI is written and tested before
anything is rendered.

**Files:**
- Modify: `build.sbt`
- Create: `modules/ui/src/main/scala/wiggly/gin/ui/Model.scala`
- Create: `modules/ui/src/main/scala/wiggly/gin/ui/Msg.scala`
- Create: `modules/ui/src/main/scala/wiggly/gin/ui/Update.scala`
- Create: `modules/ui/src/test/scala/wiggly/gin/ui/UpdateSuite.scala`

**Interfaces:**
- Consumes: `wiggly.gin.core.domain.{PlayerView, Move}`, `wiggly.gin.core.port.Credentials`.
- Produces: `Model`, `Msg`, and `Update.apply(model: Model, msg: Msg): (Model, Cmd[IO, Msg])`.
  Task 5 calls `Update.apply` from `Main`. Task 4 supplies `Api.create`, `Api.join` and `Api.play`,
  which `Update` calls and which this task stubs.

- [ ] **Step 1: Add the `ui` module to the build**

In `build.sbt`, after the `protocol` block:

```scala
lazy val tyrianVersion  = "0.14.0"
lazy val scalajsDomVersion = "2.8.0"

// The browser. JavaScript only: there is no JVM meaning for a page.
lazy val ui = project
  .in(file("modules/ui"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(protocol.js % "compile->compile;test->test")
  .settings(commonSettings)
  .settings(
    name := "wiggly-gin-ui",
    // A single file keeps the page's script tag to one line and the server's resources to one
    // generated file. Nothing here is large enough for splitting to pay.
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "io.indigoengine" %%% "tyrian-io"   % tyrianVersion,
      "org.scala-js"    %%% "scalajs-dom" % scalajsDomVersion
    )
  )
```

Add `ui` to the `root` aggregate:

```scala
  .aggregate(core.jvm, core.js, protocol.jvm, protocol.js, server, ui)
```

Add the import at the top of `build.sbt`:

```scala
import org.scalajs.linker.interface.ModuleKind
```

- [ ] **Step 2: Make sure the module compiles and its tests run on Node**

Run: `sbt ui/compile`
Expected: PASS. If the linker complains that Node is missing, install it. CI already has it.

- [ ] **Step 3: Write the model and the messages**

Create `modules/ui/src/main/scala/wiggly/gin/ui/Model.scala`:

```scala
package wiggly.gin.ui

import wiggly.gin.core.domain.PlayerView
import wiggly.gin.core.port.Credentials

/** What the browser holds.
  *
  * `Seated` keeps the view exactly as the server sent it. The server owns the redaction, and a
  * client that re-models a view is a second author of it.
  */
enum Model {
  case Start(joining: String, error: Option[String])
  case Connecting(credentials: Credentials)
  case Seated(credentials: Credentials, view: PlayerView, error: Option[String])
}
```

Create `modules/ui/src/main/scala/wiggly/gin/ui/Msg.scala`:

```scala
package wiggly.gin.ui

import wiggly.gin.core.domain.{Move, PlayerView}
import wiggly.gin.core.port.Credentials

enum Msg {
  case JoiningChanged(id: String)
  case Create
  case Join
  case Play(move: Move)
  case Took(credentials: Credentials)
  case Saw(view: PlayerView)
  case Failed(reason: String)
}
```

- [ ] **Step 4: Write the failing update tests**

Create `modules/ui/src/test/scala/wiggly/gin/ui/UpdateSuite.scala`:

```scala
package wiggly.gin.ui

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.{GameId, Player, PlayerView, Token}
import wiggly.gin.core.port.Credentials

object UpdateSuite extends SimpleIOSuite with Checkers {

  private val seat = Credentials(GameId("g1"), Player.One, Token("t1"))

  private val waiting: PlayerView = PlayerView.AwaitingOpponent(GameId("g1"), Player.One)

  pureTest("taking a seat moves off the start screen") {
    val (model, _) = Update(Model.Start("", None), Msg.Took(seat))
    expect.same(Model.Connecting(seat), model)
  }

  pureTest("the first view seats the player") {
    val (model, _) = Update(Model.Connecting(seat), Msg.Saw(waiting))
    expect.same(Model.Seated(seat, waiting, None), model)
  }

  pureTest("a later view replaces the view and keeps the seat") {
    val before     = Model.Seated(seat, waiting, Some("stale"))
    val (model, _) = Update(before, Msg.Saw(waiting))
    expect.same(Model.Seated(seat, waiting, None), model)
  }

  pureTest("a failure keeps the seat and shows the reason") {
    val before     = Model.Seated(seat, waiting, None)
    val (model, _) = Update(before, Msg.Failed("nope"))
    expect.same(Model.Seated(seat, waiting, Some("nope")), model)
  }

  pureTest("a failure before a seat exists stays on the start screen") {
    val (model, _) = Update(Model.Start("g2", None), Msg.Failed("nope"))
    expect.same(Model.Start("g2", Some("nope")), model)
  }

  pureTest("typing a game id is remembered") {
    val (model, _) = Update(Model.Start("", None), Msg.JoiningChanged("g2"))
    expect.same(Model.Start("g2", None), model)
  }
}
```

The claim worth testing is that a failure never costs the player their seat. A model that drops
back to `Start` on a transient error makes the player rejoin a game they already sit in. The
server refuses that with `AlreadyFull`.

- [ ] **Step 5: Run it and make sure it fails**

Run: `sbt "ui/testOnly wiggly.gin.ui.UpdateSuite"`
Expected: FAIL to compile, with "Not found: Update".

- [ ] **Step 6: Write the update function**

Create `modules/ui/src/main/scala/wiggly/gin/ui/Update.scala`:

```scala
package wiggly.gin.ui

import cats.effect.IO
import tyrian.Cmd

object Update {

  def apply(model: Model, msg: Msg): (Model, Cmd[IO, Msg]) = {
    (model, msg) match {
      case (Model.Start(_, error), Msg.JoiningChanged(id)) =>
        (Model.Start(id, error), Cmd.None)

      case (Model.Start(_, _), Msg.Create) =>
        (model, Api.create)

      case (Model.Start(joining, _), Msg.Join) =>
        (model, Api.join(joining))

      case (_, Msg.Took(credentials)) =>
        (Model.Connecting(credentials), Cmd.None)

      case (Model.Connecting(credentials), Msg.Saw(view)) =>
        (Model.Seated(credentials, view, None), Cmd.None)

      case (Model.Seated(credentials, _, _), Msg.Saw(view)) =>
        (Model.Seated(credentials, view, None), Cmd.None)

      case (Model.Seated(credentials, view, _), Msg.Play(move)) =>
        (Model.Seated(credentials, view, None), Api.play(credentials, move))

      case (Model.Start(joining, _), Msg.Failed(reason)) =>
        (Model.Start(joining, Some(reason)), Cmd.None)

      case (Model.Seated(credentials, view, _), Msg.Failed(reason)) =>
        (Model.Seated(credentials, view, Some(reason)), Cmd.None)

      // A message that does not belong to the state the model is in. Dropping it is right: the
      // only way to reach one is a reply arriving after the model moved on.
      case _ => (model, Cmd.None)
    }
  }
}
```

- [ ] **Step 7: Add the stub `Api` so this compiles**

Task 4 fills these in. Create `modules/ui/src/main/scala/wiggly/gin/ui/Api.scala` with the
signatures and a failing body, so that the update function is testable before the HTTP work lands:

```scala
package wiggly.gin.ui

import cats.effect.IO
import tyrian.Cmd
import wiggly.gin.core.domain.Move
import wiggly.gin.core.port.Credentials

object Api {
  def create: Cmd[IO, Msg]                                = Cmd.None
  def join(id: String): Cmd[IO, Msg]                      = Cmd.None
  def play(seat: Credentials, move: Move): Cmd[IO, Msg]   = Cmd.None
}
```

- [ ] **Step 8: Run the suite and make sure it passes**

Run: `sbt "ui/testOnly wiggly.gin.ui.UpdateSuite"`
Expected: PASS, all six cases.

- [ ] **Step 9: Run the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "Add the ui module with its model and update function"
```

---

### Task 4: Talking to the server

The commands that call the API, and the subscription over the event stream.

**Files:**
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Api.scala`
- Create: `modules/ui/src/main/scala/wiggly/gin/ui/Events.scala`
- Create: `modules/ui/src/main/scala/wiggly/gin/ui/Seat.scala`
- Create: `modules/ui/src/test/scala/wiggly/gin/ui/SeatSuite.scala`

**Interfaces:**
- Consumes: `wiggly.gin.protocol.Codecs`, `Msg`, `Model`.
- Produces: `Api.create`, `Api.join`, `Api.play` returning `Cmd[IO, Msg]`.
  `Events.watch(credentials: Credentials): Sub[IO, Msg]`.
  `Seat.remember(seat: Credentials): Unit`, `Seat.recall(id: GameId): Option[Credentials]`,
  `Seat.gameIdIn(path: String): Option[String]`.

- [ ] **Step 1: Write the commands**

Replace the stub body of `Api.scala`:

```scala
package wiggly.gin.ui

import cats.effect.IO
import io.circe.syntax.*
import tyrian.Cmd
import tyrian.http.*
import wiggly.gin.core.domain.Move
import wiggly.gin.core.port.Credentials
import wiggly.gin.protocol.Codecs.given

/** Every call the browser makes. The UI reaches the server through the public routes and nothing
  * else, so a UI that works is evidence that the API works.
  */
object Api {

  private val prefix = "/api/v1/games"

  val create: Cmd[IO, Msg] =
    Http.send(Request.post(prefix, Body.empty), credentialsResponse)

  def join(id: String): Cmd[IO, Msg] =
    Http.send(Request.post(s"$prefix/$id/join", Body.empty), credentialsResponse)

  def play(seat: Credentials, move: Move): Cmd[IO, Msg] = {
    val request = Request
      .post(s"$prefix/${seat.id.value}/moves", Body.json(move.asJson.noSpaces))
      .withHeaders(Header("Authorization", s"Bearer ${seat.token.value}"))

    Http.send(request, viewResponse)
  }

  private val credentialsResponse: Decoder[Msg] =
    decodeOr[Credentials](Msg.Took.apply)

  private val viewResponse: Decoder[Msg] =
    decodeOr[wiggly.gin.core.domain.PlayerView](Msg.Saw.apply)

  /** A failed request becomes a message rather than an exception, because the model has somewhere
    * to put a reason and nowhere to put a throwable.
    */
  private def decodeOr[A: io.circe.Decoder](onSuccess: A => Msg): Decoder[Msg] =
    Decoder[Msg](
      onResponse = response =>
        io.circe.parser.decode[A](response.body) match {
          case Right(value) => onSuccess(value)
          case Left(error)  => Msg.Failed(error.getMessage)
        },
      onError = error => Msg.Failed(error.toString)
    )
}
```

If `Request.post` or `Body.json` do not match Tyrian 0.14.0's names, run
`unzip -l ~/.cache/coursier/**/tyrian_sjs1_3-0.14.0.jar | grep http` and read the signatures with
`javap` rather than guessing. The shape above is the intent: a POST, a JSON body, a bearer header,
and a decode that turns both success and failure into a `Msg`.

- [ ] **Step 2: Write the event subscription**

Create `modules/ui/src/main/scala/wiggly/gin/ui/Events.scala`:

```scala
package wiggly.gin.ui

import cats.effect.IO
import org.scalajs.dom
import tyrian.Sub
import wiggly.gin.core.domain.PlayerView
import wiggly.gin.core.port.Credentials
import wiggly.gin.protocol.Codecs.given

/** The server's stream of views, as a subscription.
  *
  * Tyrian ships a subscription for WebSockets and none for server-sent events, so this is the one
  * facade the UI needs. The token goes in the query rather than a header because `EventSource`
  * cannot set one, and the routes already accept it there.
  */
object Events {

  def watch(seat: Credentials): Sub[IO, Msg] = {
    val url = s"/api/v1/games/${seat.id.value}/events?token=${seat.token.value}"

    Sub.make[IO, dom.MessageEvent, dom.EventSource](
      id = s"events-${seat.id.value}",
      acquire = emit => {
        val source = new dom.EventSource(url)
        source.onmessage = event => emit(Right(event))
        source.onerror = _ => ()
        IO(source)
      },
      release = source => IO(source.close())
    ).map(event => decode(event.data.toString))
  }

  private def decode(payload: String): Msg = {
    io.circe.parser.decode[PlayerView](payload) match {
      case Right(view) => Msg.Saw(view)
      case Left(error) => Msg.Failed(error.getMessage)
    }
  }
}
```

`onerror` does nothing on purpose. A browser reconnects a dropped `EventSource` on its own.
Turning a reconnect into an error message shows the player a failure that already fixed itself.

The exact arity of `Sub.make` in 0.14.0 is:

```
make[F, A, R](id: String, acquire: (Either[Throwable, A] => Unit) => F[R], release: R => F[Unit])
```

If the compiler disagrees, read the real one with `javap -cp <tyrian jar> 'tyrian.Sub$'`.

- [ ] **Step 3: Write the failing seat test**

Create `modules/ui/src/test/scala/wiggly/gin/ui/SeatSuite.scala`:

```scala
package wiggly.gin.ui

import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{GameId, Player, Token}
import wiggly.gin.core.port.Credentials

object SeatSuite extends SimpleIOSuite {

  private val seat = Credentials(GameId("g1"), Player.Two, Token("t1"))

  pureTest("a remembered seat is recalled for its own game") {
    Seat.remember(seat)
    expect.same(Some(seat), Seat.recall(GameId("g1")))
  }

  pureTest("a seat is not recalled for a different game") {
    Seat.remember(seat)
    expect.same(None, Seat.recall(GameId("g2")))
  }

  pureTest("a game id is read out of a path") {
    expect.same(Some("g1"), Seat.gameIdIn("/g1"))
  }

  pureTest("the root path names no game") {
    expect.same(None, Seat.gameIdIn("/"))
  }
}
```

- [ ] **Step 4: Run it and make sure it fails**

Run: `sbt "ui/testOnly wiggly.gin.ui.SeatSuite"`
Expected: FAIL to compile, with "Not found: Seat".

- [ ] **Step 5: Write the seat store**

Create `modules/ui/src/main/scala/wiggly/gin/ui/Seat.scala`:

```scala
package wiggly.gin.ui

import org.scalajs.dom
import wiggly.gin.core.domain.{GameId, Player, Token}
import wiggly.gin.core.port.Credentials

/** Where a player's claim to a seat is kept between page loads.
  *
  * A refresh that loses the token loses the seat, and the token is the whole of a player's claim
  * to one. It is keyed by game, so opening a second game does not evict the first.
  */
object Seat {

  def remember(seat: Credentials): Unit = {
    dom.window.sessionStorage.setItem(key(seat.id), s"${seat.you.toString}:${seat.token.value}")
  }

  def recall(id: GameId): Option[Credentials] = {
    Option(dom.window.sessionStorage.getItem(key(id))).flatMap { stored =>
      stored.split(":", 2) match {
        case Array(player, token) =>
          Player.values.find(_.toString == player).map(Credentials(id, _, Token(token)))
        case _ => None
      }
    }
  }

  /** The game id a URL names, which is what makes the waiting screen's link work. */
  def gameIdIn(path: String): Option[String] = {
    Option(path).map(_.stripPrefix("/")).filter(_.nonEmpty)
  }
}
```

- [ ] **Step 6: Run the suite and make sure it passes**

Run: `sbt "ui/testOnly wiggly.gin.ui.SeatSuite"`
Expected: PASS, all four cases. Scala.js on Node provides `sessionStorage` through jsdom. If it
does not resolve, add `jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv()` to the `ui`
settings and `addSbtPlugin("org.scala-js" % "sbt-jsdom-nodejs-env" % "1.1.0")` to
`project/plugins.sbt`.

- [ ] **Step 7: Run the whole suite and the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Call the game API and watch its event stream from the browser"
```

---

### Task 5: The screens

**Files:**
- Create: `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`
- Create: `modules/ui/src/main/scala/wiggly/gin/ui/Main.scala`
- Create: `modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala`

**Interfaces:**
- Consumes: `Model`, `Msg`, `Moves.available`, `Update.apply`, `Events.watch`, `Seat`.
- Produces: `View.apply(model: Model): Html[Msg]`, and a `Main` that Tyrian runs.

- [ ] **Step 1: Write the failing view tests**

A `Html[Msg]` is a pure value, so a test reads its structure with no browser. Create
`modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala`:

```scala
package wiggly.gin.ui

import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{GameId, Player, PlayerView, Token}
import wiggly.gin.core.port.Credentials

object ViewSuite extends SimpleIOSuite {

  private val seat = Credentials(GameId("g1"), Player.One, Token("t1"))

  pureTest("the start screen offers a way in") {
    val rendered = render(View(Model.Start("", None)))
    expect(rendered.contains("Create a game")) and expect(rendered.contains("Join"))
  }

  pureTest("the waiting screen shows the game id to share") {
    val waiting  = PlayerView.AwaitingOpponent(GameId("g1"), Player.One)
    val rendered = render(View(Model.Seated(seat, waiting, None)))
    expect(rendered.contains("g1"))
  }

  pureTest("an error is shown to the player") {
    val waiting  = PlayerView.AwaitingOpponent(GameId("g1"), Player.One)
    val rendered = render(View(Model.Seated(seat, waiting, Some("that is not your turn"))))
    expect(rendered.contains("that is not your turn"))
  }

  /** Tyrian renders an `Html` to a string, which is all these assertions need. */
  private def render(html: tyrian.Html[Msg]): String = html.toString
}
```

- [ ] **Step 2: Run it and make sure it fails**

Run: `sbt "ui/testOnly wiggly.gin.ui.ViewSuite"`
Expected: FAIL to compile, with "Not found: View".

- [ ] **Step 3: Write the screens**

Create `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`. The structure below is the contract
for the file, and the helpers after it complete the screens:

```scala
package wiggly.gin.ui

import tyrian.Html
import tyrian.Html.*
import wiggly.gin.core.domain.*

object View {

  def apply(model: Model): Html[Msg] = {
    model match {
      case Model.Start(joining, error)    => start(joining, error)
      case Model.Connecting(_)            => div(cls := "waiting")(text("Taking your seat..."))
      case Model.Seated(_, view, error)   => seated(view, error)
    }
  }

  private def start(joining: String, error: Option[String]): Html[Msg] = {
    div(cls := "start")(
      button(onClick(Msg.Create))(text("Create a game")),
      input(value := joining, onInput(Msg.JoiningChanged.apply), placeholder := "game id"),
      button(onClick(Msg.Join))(text("Join")),
      problem(error)
    )
  }

  private def seated(view: PlayerView, error: Option[String]): Html[Msg] = {
    view match {
      case waiting: PlayerView.AwaitingOpponent => awaiting(waiting, error)
      case playing: PlayerView.InPlay           => table(playing, error)
      case over: PlayerView.Over                => finished(over)
    }
  }

  private def awaiting(view: PlayerView.AwaitingOpponent, error: Option[String]): Html[Msg] = {
    div(cls := "waiting")(
      h1(text("Waiting for the other player")),
      p(text("Send them this game: ")),
      code(text(view.id.value)),
      problem(error)
    )
  }

  private def table(view: PlayerView.InPlay, error: Option[String]): Html[Msg] = {
    div(cls := "table")(
      scoreboard(view),
      opponent(view),
      pile(view),
      hand(view),
      moves(view),
      view.previous.map(lastRound).getOrElse(empty),
      problem(error)
    )
  }

  /** Every button on the table comes from the same list the rules would accept, so a button that
    * is offered is a move that works.
    */
  private def moves(view: PlayerView.InPlay): Html[Msg] = {
    div(cls := "moves")(
      Moves.available(view).map { move =>
        button(onClick(Msg.Play(move)))(text(label(move)))
      }
    )
  }

  private def label(move: Move): String = {
    move match {
      case Move.DrawStock      => "Draw from the stock"
      case Move.DrawDiscard    => "Take the upcard"
      case Move.Pass           => "Pass"
      case Move.Discard(card)  => s"Discard ${show(card)}"
      case Move.Knock(card)    => s"Knock, discarding ${show(card)}"
    }
  }

  private def show(card: Card): String = s"${card.rank} of ${card.suit}"

  private def problem(error: Option[String]): Html[Msg] = {
    error.map(reason => div(cls := "error")(text(reason))).getOrElse(empty)
  }

  private val empty: Html[Msg] = span()()
}
```

The remaining helpers go in the same file. `hand` and `scoreboard` are written out here because
they carry the two rules worth stating. Write `opponent`, `pile`, `lastRound` and `finished` to
match, reading only fields the spec's table lists:

```scala
  /** A hand reads as its arrangement: each meld on its own row, the deadwood last. That is the
    * grouping the player needs, because it is the one the score is counted from.
    */
  private def hand(view: PlayerView.InPlay): Html[Msg] = {
    div(cls := "hand")(
      div(cls := "melds")(
        view.arrangement.melds.map { meld =>
          div(cls := "meld")(meld.cards.toList.map(card))
        }
      ),
      div(cls := "deadwood")(view.arrangement.deadwood.map(card)),
      p(cls := "count")(text(s"Deadwood: ${view.arrangement.deadwoodValue}"))
    )
  }

  private def card(value: Card): Html[Msg] = {
    val colour = value.suit match {
      case Suit.Hearts | Suit.Diamonds => "red"
      case Suit.Clubs | Suit.Spades    => "black"
    }

    span(cls := s"card $colour")(text(show(value)))
  }

  private def scoreboard(view: PlayerView.InPlay): Html[Msg] = {
    val turn =
      if (view.onTurn === view.you) "Your turn" else "Waiting for the other player"

    div(cls := "scoreboard")(
      p(text(s"You: ${view.totals(view.you)}")),
      p(text(s"Them: ${view.totals(view.you.other)}")),
      p(cls := "turn")(text(turn))
    )
  }
```

`opponent` shows `view.opponentSize` as a count and never a card. `pile` shows `view.upcard`,
`view.stockSize` and `view.discardSize`. `lastRound` renders a `RoundResult`, and `finished`
renders `PlayerView.Over`, which carries a `MatchResult` and the round that ended it.

Add `import cats.syntax.all.*` for `===`.

- [ ] **Step 4: Run the view suite and make sure it passes**

Run: `sbt "ui/testOnly wiggly.gin.ui.ViewSuite"`
Expected: PASS, all three cases.

- [ ] **Step 5: Write the entry point**

Create `modules/ui/src/main/scala/wiggly/gin/ui/Main.scala`:

```scala
package wiggly.gin.ui

import cats.effect.IO
import org.scalajs.dom
import tyrian.*

import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("main")
object Main extends TyrianIOApp[Msg, Model] {

  def router: Location => Msg = Routing.none(Msg.Failed("no such page"))

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) = {
    val arriving = Seat.gameIdIn(dom.window.location.pathname)

    arriving.flatMap(id => Seat.recall(GameId(id))) match {
      case Some(seat) => (Model.Connecting(seat), Cmd.None)
      case None       => (Model.Start(arriving.getOrElse(""), None), Cmd.None)
    }
  }

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = msg => {
    // Remembering the seat is the one effect the update needs, and it belongs where the seat is
    // first known rather than in a command that could be dropped.
    msg match {
      case Msg.Took(seat) => Seat.remember(seat)
      case _              => ()
    }

    Update(model, msg)
  }

  def view(model: Model): Html[Msg] = View(model)

  def subscriptions(model: Model): Sub[IO, Msg] = {
    model match {
      case Model.Connecting(seat)     => Events.watch(seat)
      case Model.Seated(seat, _, _)   => Events.watch(seat)
      case Model.Start(_, _)          => Sub.None
    }
  }
}
```

If `GameId` does not resolve, add `import wiggly.gin.core.domain.GameId`.

- [ ] **Step 6: Exclude the entry point and the view from coverage**

In `build.sbt`, extend the existing exclusion:

```scala
ThisBuild / coverageExcludedFiles := ".*/server/Main;.*/server/HttpServer;.*/ui/Main;.*/ui/View"
```

The model, `Update` and `Moves.available` stay in the figure. They are where the logic is.

- [ ] **Step 7: Run the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Draw the screens a player plays a match through"
```

---

### Task 6: Serve the page from the server

**Files:**
- Create: `modules/server/src/main/resources/web/index.html`
- Create: `modules/server/src/main/resources/web/gin.css`
- Create: `modules/server/src/main/scala/wiggly/gin/server/adapter/http/WebRoutes.scala`
- Modify: `modules/server/src/main/scala/wiggly/gin/server/adapter/http/GinApi.scala`
- Create: `modules/server/src/test/scala/wiggly/gin/server/adapter/http/WebRoutesSuite.scala`
- Modify: `build.sbt`

**Interfaces:**
- Consumes: `GinApi.httpApp`.
- Produces: `WebRoutes.apply[F]: HttpRoutes[F]`.

- [ ] **Step 1: Write the page and the styling**

Create `modules/server/src/main/resources/web/index.html`:

```html
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Gin Rummy</title>
    <link rel="stylesheet" href="/gin.css" />
  </head>
  <body>
    <div id="app"></div>
    <script type="module">
      import { main } from "/main.js";
      main("app");
    </script>
  </body>
</html>
```

Create `modules/server/src/main/resources/web/gin.css`:

```css
:root { --ink: #1b1b1b; --paper: #f4f1ea; --line: #c9c3b6; }

body {
  margin: 0 auto;
  padding: 1rem;
  max-width: 44rem;
  font: 1rem/1.5 system-ui, sans-serif;
  color: var(--ink);
  background: var(--paper);
}

.card {
  display: inline-block;
  margin: 0 0.2rem 0.3rem 0;
  padding: 0.3rem 0.5rem;
  border: 1px solid var(--line);
  border-radius: 0.25rem;
  background: #fff;
  white-space: nowrap;
}

.card.red { color: #a3121a; }
.card.black { color: var(--ink); }

.meld { margin-bottom: 0.3rem; }
.deadwood { margin-top: 0.6rem; opacity: 0.85; }
.moves button { margin: 0 0.3rem 0.3rem 0; }
.error { margin-top: 1rem; color: #a3121a; }
.turn { font-weight: 600; }
```

One column at any width, so a narrow screen needs no separate rule. The suit colour is the one
piece of styling that carries meaning rather than decoration.

- [ ] **Step 2: Write the failing route test**

Create `modules/server/src/test/scala/wiggly/gin/server/adapter/http/WebRoutesSuite.scala`:

```scala
package wiggly.gin.server.adapter.http

import cats.effect.IO
import org.http4s.*
import org.http4s.implicits.*
import weaver.SimpleIOSuite

object WebRoutesSuite extends SimpleIOSuite {

  private val routes = WebRoutes[IO]

  test("the root serves the page") {
    routes.orNotFound.run(Request[IO](Method.GET, uri"/")).map { response =>
      expect.same(Status.Ok, response.status)
    }
  }

  test("a path that names no file serves the page, so a game link opens the app") {
    routes.orNotFound.run(Request[IO](Method.GET, uri"/some-game-id")).map { response =>
      expect.same(Status.Ok, response.status)
    }
  }

  test("a file that exists is served as itself") {
    routes.orNotFound.run(Request[IO](Method.GET, uri"/gin.css")).map { response =>
      expect.same(Status.Ok, response.status) and
        expect(response.contentType.exists(_.mediaType == MediaType.text.css))
    }
  }
}
```

Add to the same file a suite that pins the ordering rule, because it is the one a later edit
breaks:

```scala
object GinApiWebSuite extends SimpleIOSuite {

  test("health answers before the page fallback does") {
    val app = GinApi.httpApp[IO](HttpRoutes.empty[IO])

    app.run(Request[IO](Method.GET, uri"/health")).map { response =>
      expect.same(Status.Ok, response.status)
    }
  }
}
```

- [ ] **Step 3: Run it and make sure it fails**

Run: `sbt "server/testOnly wiggly.gin.server.adapter.http.WebRoutesSuite"`
Expected: FAIL to compile, with "Not found: WebRoutes".

- [ ] **Step 4: Write the routes**

Create `modules/server/src/main/scala/wiggly/gin/server/adapter/http/WebRoutes.scala`:

```scala
package wiggly.gin.server.adapter.http

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all.*
import org.http4s.*
import org.http4s.server.staticcontent.*

/** The browser application, served from the jar.
  *
  * A path that names no file falls back to the page, because a link to a game is a path the
  * application routes rather than a file on disk. The fallback is last, so it cannot take a
  * request a real file answers.
  */
object WebRoutes {

  private val Root = "/web"

  def apply[F[_]: Async]: HttpRoutes[F] = {
    val files = resourceServiceBuilder[F](Root).toRoutes

    HttpRoutes[F] { request =>
      files(request).orElse(OptionT.liftF(page(request)))
    }
  }

  private def page[F[_]: Async](request: Request[F]): F[Response[F]] = {
    StaticFile
      .fromResource[F](s"$Root/index.html", Some(request))
      .getOrElseF(Async[F].pure(Response[F](Status.NotFound)))
  }
}
```

- [ ] **Step 5: Mount it, after the health route**

In `GinApi.scala`, change the router. The order carries two rules, so it is stated rather than
left to whichever route matches first:

```scala
  def httpApp[F[_]: Async](gameRoutes: HttpRoutes[F]): HttpApp[F] = {
    val routes = Router(
      ApiPrefix -> gameRoutes,
      "/"       -> (HealthRoutes[F] <+> WebRoutes[F])
    )

    jsonErrors(routes)
  }
```

Add `import cats.syntax.semigroupk.*` for `<+>`, and widen the constraint from `Concurrent` to
`Async`, which `WebRoutes` needs to read a file. If `Main.scala` names the constraint, widen it
there too.

`/api/v1` and `/health` both answer before the UI does, so the fallback cannot swallow either. The
static routes are inside `jsonErrors` rather than after it, because a route added after it never
runs.

- [ ] **Step 6: Wire the linker output into the server's resources**

In `build.sbt`, add to the `server` settings:

```scala
    // The browser application is part of the server's resources, so one command still builds one
    // image and that image contains the UI. fullLinkJS rather than fastLinkJS: this is what ships.
    Compile / resourceGenerators += Def.task {
      val linked = (ui / Compile / fullLinkJS).value.data
      val target = (Compile / resourceManaged).value / "web"

      IO.copyDirectory(linked, target)
      (target ** "*").get.filter(_.isFile)
    }.taskValue,
```

`IO` here is `sbt.io.IO`, not `cats.effect.IO`. The sbt file has no cats-effect import, so there is
no clash.

- [ ] **Step 7: Run the suites and make sure they pass**

Run: `sbt "server/testOnly wiggly.gin.server.adapter.http.*"`
Expected: PASS. `GameRoutesSuite` and `RedactionSuite` must still pass unchanged: the new routes
sit beside the API rather than in front of it.

- [ ] **Step 8: Run the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Serve the browser application from the server"
```

---

### Task 7: Play a whole match through the container

The checks a machine cannot make for you, and the documents.

**Files:**
- Modify: `README.md`
- Modify: `docs/DESIGN.md`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Build the image and play a game in it**

```bash
sbt server/Docker/publishLocal
docker run --rm -p 8080:8080 wiggly-gin:latest
```

Open `http://localhost:8080` in two browser windows. In the first, create a game. Copy the id into
the second window and join. Then play a whole match to 100 points.

Make sure that each of these holds, and fix what does not:

| Check | Why it is here |
| --- | --- |
| Both windows update without a reload | The event stream reaches the browser. |
| Neither window shows the other hand | The redaction survives the trip. |
| Only legal moves appear as buttons | `Moves.available` agrees with the server. |
| A refresh keeps your seat | The token survives in `sessionStorage`. |
| A round ending deals the next one | The game keeps no finished round. |
| The match ends at 100 and says who won | The ledger reaches the browser. |

- [ ] **Step 2: Make sure the page is read from the environment, not baked in**

```bash
docker run --rm -e GIN_HTTP_PORT=9090 -p 9090:9090 wiggly-gin:latest
curl -s localhost:9090/health
```

Expected: the health route answers on 9090, and the page loads there too.

- [ ] **Step 3: Add the UI to the pipeline's smoke test**

In `.github/workflows/ci.yml`, the step that starts the image already waits for `/health`. Add one
line to it that makes sure the page is in the image:

```yaml
          curl -fsS localhost:8080/ | grep -q 'id="app"'
```

A `/health` that answers proves the server started. This proves the resource generator ran.

- [ ] **Step 4: Update the documents**

`README.md` gains the `protocol` and `ui` modules in its table and its tree. It also gains a line
on opening the UI in a browser, and a note that Scala.js suites run on Node.

`docs/DESIGN.md` gains a section on the browser adapter, beside the one on the HTTP adapter. A
shared link is now a way to find a game. The "What comes later" entry that offers a lobby as the
only way therefore loses that claim. Leave the WebSocket entry alone, because it is still later
work.

`docs/SPEC-WEB-UI.md` gains a note wherever the code came out different from it, in the style
[`docs/DESIGN.md`](DESIGN.md) already uses.

- [ ] **Step 5: Run the gate and the coverage floors**

```bash
SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test
sbt coverageAll
```

Expected: both PASS. If the coverage floor trips, the fix is a test for the model or for
`Moves.available`, not a wider exclusion.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Write down the browser adapter and check it in the pipeline"
```

---

## What this plan does not do

Each of these is in the spec's deliberately-absent list and stays there: no WebSocket, no lobby, no
accounts, no offline play, no animation and no responsive layout past a single readable column.

Two things are worth knowing while the work is in progress.

The exact names in Tyrian 0.14.0's `Http` and `Sub` are the likeliest place for this plan to be
wrong. They were read from a jar listing rather than from a compiler. Task 4 says how to read the
real signatures. The shapes are right even where a name is not.

The resource generator makes `sbt server/test` depend on linking the UI, which adds time to a run
that is already slow from cold. If that becomes annoying, attach the generator to
`Compile / packageBin` rather than to `resourceGenerators`. The cost is that `sbt server/run` then
stops serving the page.
