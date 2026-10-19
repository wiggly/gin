# Lobby implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two people start a game without sharing a code. Each player gives a name, and the waiting
games show in a live list on the start screen.

**Architecture:** A pure `Lobby` value in `core/domain` holds the rows. A `LobbyBoard` port stores
it, and a `SignallingRef` adapter in `server` streams it. `Games.watch` is the only writer: a host's
stream on a waiting game lists the game while the stream is open. Names live on `Game` and reach
both players through `PlayerView`.

**Tech Stack:** Scala 3.9.0, cats-effect 3.6.3, fs2 3.14.0, http4s 0.23.37, circe 0.14.16, Tyrian
0.14.0, weaver 0.13.0, sbt 1.13.0.

**Spec:** [`docs/SPEC-LOBBY.md`](SPEC-LOBBY.md)

## Global Constraints

- Scala 3.9.0, sbt 1.13.0, JDK 25.
- Braces delimit scope. Never indentation syntax. This covers every definition, `match` case and
  `if` body.
- A type that carries an invariant has a private constructor and a smart constructor that returns
  `Option`. Nothing downstream checks the invariant again.
- Tests are weaver suites, objects named `*Suite`, property-based by default with
  `weaver-scalacheck`. `pureTest` is for a case worth pinning literally. Never munit.
- Generators live in `modules/core/src/test/scala/wiggly/gin/gen/`. Fakes live in
  `modules/core/src/test/scala/wiggly/gin/fake/`. Pass an explicit `Gen` to `forall`.
- weaver does not shrink. Keep generators narrow, so that a counterexample stays readable.
- Codecs are derived. Write one by hand only where the derived shape is wrong for the API, and say
  which in a comment. Codecs never live in `core`.
- Comments say why, never what. Default to none.
- `scalacOptions` are managed by sbt-tpolecat. Never set them directly.
- Commit messages are one subject line and nothing else. No `Co-Authored-By` and no generated-by
  footer.
- Each commit builds and tests green on its own.
- A full `sbt test` from cold takes about 90 seconds. That is not a hang. Do not pipe sbt through
  `grep` while you watch it.
- If sbt fails with `NoSuchFileException` under `classes.bak`, run the command again after `clean`.
  It is an incremental compile race and not a code error.
- The gate, before handing the work over:
  `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`

The sbt project ids are `coreJVM`, `coreJS`, `protocolJVM`, `protocolJS`, `server` and `ui`.

## File structure

| File | Responsibility |
| --- | --- |
| `modules/core/src/main/scala/wiggly/gin/core/domain/PlayerName.scala` | New. A name and its rule. |
| `modules/core/src/main/scala/wiggly/gin/core/domain/Game.scala` | `Names`, and a name on every state of a game. |
| `modules/core/src/main/scala/wiggly/gin/core/domain/PlayerView.scala` | The names in every view. |
| `modules/core/src/main/scala/wiggly/gin/core/domain/Lobby.scala` | New. `ListedAt`, `Listing` and `Lobby`. |
| `modules/core/src/main/scala/wiggly/gin/core/port/GameService.scala` | `create` and `join` take a name. |
| `modules/core/src/main/scala/wiggly/gin/core/port/LobbyBoard.scala` | New. Where the lobby is kept. |
| `modules/core/src/main/scala/wiggly/gin/core/port/LobbyService.scala` | New. The lobby as a client reads it. |
| `modules/core/src/main/scala/wiggly/gin/core/service/Games.scala` | Names, and the presence rule in `watch`. |
| `modules/core/src/main/scala/wiggly/gin/core/service/Lobbies.scala` | New. `LobbyService` over the board. |
| `modules/core/src/test/scala/wiggly/gin/gen/NameGen.scala` | New. Names for tests. |
| `modules/core/src/test/scala/wiggly/gin/gen/LobbyGen.scala` | New. Sequences of lobby transitions. |
| `modules/core/src/test/scala/wiggly/gin/fake/FakeLobbyBoard.scala` | New. A board that keeps its history. |
| `modules/protocol/src/main/scala/wiggly/gin/protocol/Introduction.scala` | New. The body of a request for a seat. |
| `modules/protocol/src/main/scala/wiggly/gin/protocol/Codecs.scala` | Codecs for the new types. |
| `modules/server/src/main/scala/wiggly/gin/server/adapter/memory/MemoryLobbyBoard.scala` | New. The board in a `SignallingRef`. |
| `modules/server/src/main/scala/wiggly/gin/server/adapter/http/EventStreams.scala` | New. Values as server-sent events with a heartbeat. |
| `modules/server/src/main/scala/wiggly/gin/server/adapter/http/GameRoutes.scala` | Reads a name from the body of create and join. |
| `modules/server/src/main/scala/wiggly/gin/server/adapter/http/LobbyRoutes.scala` | New. `GET /lobby/events`. |
| `modules/server/src/main/scala/wiggly/gin/server/Main.scala` | Wires the board and the lobby routes. |
| `modules/server/src/test/scala/wiggly/gin/server/LobbyPresenceSuite.scala` | New. The presence rule against the memory adapters. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Profile.scala` | New. The name in `localStorage`. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Waiting.scala` | New. How long a game waited, in words. |
| `modules/ui/src/main/scala/wiggly/gin/ui/{Model,Msg,Update,Api,Events,View,Main}.scala` | The name box, the lobby, and names at the table. |
| `docs/openapi.yaml`, `bruno/` | The bodies, the new route and the new refusal. |
| `docs/DESIGN.md`, `docs/SPEC-LOBBY.md` | The new ports, and where the code came out different. |

The presence suite lives in `server` and not in `core`. It needs a live event stream, and the
fake in `core` replays a finite history. The spec already says so.

---

### Task 1: A player's name

**Files:**
- Create: `modules/core/src/main/scala/wiggly/gin/core/domain/PlayerName.scala`
- Create: `modules/core/src/test/scala/wiggly/gin/gen/NameGen.scala`
- Test: `modules/core/src/test/scala/wiggly/gin/core/domain/PlayerNameSuite.scala`

**Interfaces:**
- Consumes: nothing.
- Produces: `PlayerName` with `value: String`, `PlayerName.from(raw: String): Option[PlayerName]`,
  `PlayerName.MaxLength: Int`, and `given Eq[PlayerName]`, `given Show[PlayerName]`.
  `NameGen.name: Gen[PlayerName]` and `NameGen.named(raw: String): PlayerName`.

- [ ] **Step 1: Write the generator**

```scala
package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.domain.PlayerName

object NameGen {

  /** A name for a test that does not care which. Letters and digits only, so that a name never
    * collides with the punctuation a rendered page or a payload is searched for.
    */
  val name: Gen[PlayerName] = for {
    length <- Gen.choose(1, PlayerName.MaxLength)
    chars  <- Gen.listOfN(length, Gen.alphaNumChar)
  } yield named(chars.mkString)

  def named(raw: String): PlayerName =
    PlayerName.from(raw).getOrElse(sys.error(s"not a name: '$raw'"))
}
```

- [ ] **Step 2: Write the failing suite**

```scala
package wiggly.gin.core.domain

import cats.implicits.*
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object PlayerNameSuite extends SimpleIOSuite with Checkers {

  private def letters(min: Int, max: Int): Gen[String] =
    Gen.choose(min, max).flatMap(Gen.listOfN(_, Gen.alphaChar)).map(_.mkString)

  test("a name of 1 to 20 letters is accepted as it was typed") {
    forall(letters(1, PlayerName.MaxLength)) { raw =>
      expect.eql(PlayerName.from(raw).map(_.value), Some(raw))
    }
  }

  test("space around a name is not part of it") {
    forall(letters(1, PlayerName.MaxLength)) { raw =>
      expect.eql(PlayerName.from(s"  $raw\t"), PlayerName.from(raw))
    }
  }

  test("a name longer than 20 is refused") {
    forall(letters(PlayerName.MaxLength + 1, 40)) { raw =>
      expect.eql(PlayerName.from(raw), None)
    }
  }

  test("a control character anywhere in a name refuses it") {
    val spoiled = for {
      raw     <- letters(2, PlayerName.MaxLength - 1)
      at      <- Gen.choose(1, raw.length - 1)
      control <- Gen.choose('\u0000', '\u001f')
    } yield raw.patch(at, control.toString, 0)

    forall(spoiled)(raw => expect.eql(PlayerName.from(raw), None))
  }

  pureTest("nothing, or nothing but space, is not a name") {
    expect.eql(PlayerName.from(""), None) and expect.eql(PlayerName.from("   "), None)
  }

  pureTest("the limit counts characters a person sees, not UTF-16 units") {
    val face = "😀"

    expect(PlayerName.from(face * PlayerName.MaxLength).isDefined) and
      expect.eql(PlayerName.from(face * (PlayerName.MaxLength + 1)), None)
  }
}
```

- [ ] **Step 3: Run it to make sure that it fails**

Run: `sbt "coreJVM/testOnly wiggly.gin.core.domain.PlayerNameSuite"`
Expected: compile failure, `Not found: PlayerName`.

- [ ] **Step 4: Write the type**

```scala
package wiggly.gin.core.domain

import cats.{Eq, Show}

/** What a player calls themselves. A label and never a claim: two players can share one, and the
  * token stays the only thing that seats anybody.
  */
final case class PlayerName private (value: String)

object PlayerName {

  val MaxLength: Int = 20

  /** Counted in code points, so that a name outside the Basic Multilingual Plane gets the same
    * twenty as one in ASCII.
    */
  def from(raw: String): Option[PlayerName] = {
    val trimmed = raw.trim
    val length  = trimmed.codePointCount(0, trimmed.length)

    Option.when(length >= 1 && length <= MaxLength && !trimmed.exists(Character.isISOControl))(
      PlayerName(trimmed)
    )
  }

  given Eq[PlayerName] = Eq.fromUniversalEquals

  given Show[PlayerName] = Show.fromToString
}
```

- [ ] **Step 5: Run it to make sure that it passes**

Run: `sbt "coreJVM/testOnly wiggly.gin.core.domain.PlayerNameSuite" "coreJS/testOnly wiggly.gin.core.domain.PlayerNameSuite"`
Expected: PASS on both platforms. `core` is cross-built, so the rule has to hold on Scala.js too.

- [ ] **Step 6: Commit**

```bash
git add modules/core/src/main/scala/wiggly/gin/core/domain/PlayerName.scala \
        modules/core/src/test/scala/wiggly/gin/gen/NameGen.scala \
        modules/core/src/test/scala/wiggly/gin/core/domain/PlayerNameSuite.scala
git commit -m "Give a player a name"
```

---

### Task 2: Carry both names through the game, the service and the wire

This task changes the signatures of `Game`, `PlayerView` and `GameService`. Every module compiles
against them, so the fixtures in every module change in the same commit. After this task the
server asks for a name and the browser does not send one yet. Task 3 closes that gap. Run Task 3
straight after this one.

**Files:**
- Modify: `modules/core/src/main/scala/wiggly/gin/core/domain/Game.scala`
- Modify: `modules/core/src/main/scala/wiggly/gin/core/domain/PlayerView.scala`
- Modify: `modules/core/src/main/scala/wiggly/gin/core/port/GameService.scala`
- Modify: `modules/core/src/main/scala/wiggly/gin/core/service/Games.scala`
- Create: `modules/protocol/src/main/scala/wiggly/gin/protocol/Introduction.scala`
- Modify: `modules/protocol/src/main/scala/wiggly/gin/protocol/Codecs.scala`
- Modify: `modules/server/src/main/scala/wiggly/gin/server/adapter/http/GameRoutes.scala`
- Modify (fixtures): `modules/core/src/test/scala/wiggly/gin/gen/GameGen.scala`,
  `modules/core/src/test/scala/wiggly/gin/core/domain/MovesSuite.scala`,
  `modules/server/src/test/scala/wiggly/gin/server/adapter/memory/MemoryGameEventsSuite.scala`,
  `modules/server/src/test/scala/wiggly/gin/server/adapter/memory/MemoryGameRepositorySuite.scala`,
  `modules/ui/src/test/scala/wiggly/gin/ui/UpdateSuite.scala`,
  `modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala`
- Test: `GameSuite`, `PlayerViewSuite`, `GamesSuite` in `core`, `CodecsSuite` in `protocol`,
  `GameRoutesSuite` in `server`
- Modify: `docs/openapi.yaml`, `bruno/Seats/Create game.bru`, `bruno/Seats/Join game.bru`
- Create: `bruno/Refusals/400 Malformed name.bru`

**Interfaces:**
- Consumes: `PlayerName`, `NameGen.name`, `NameGen.named` from Task 1.
- Produces:
  - `final case class Names(one: PlayerName, two: PlayerName)` with `apply(player: Player): PlayerName`.
  - `Game.AwaitingOpponent(id: GameId, host: Token, hostName: PlayerName)`.
  - `Game.InPlay(id, tokens, names: Names, ledger, round, previous)`.
  - `Game.Over(id, tokens, names: Names, ledger, previous)`.
  - `Game.joined(guest: Token, guestName: PlayerName, deck: Deck): Either[GameFault, Game]`.
  - `PlayerView.AwaitingOpponent(id: GameId, you: Player, name: PlayerName)`.
  - `PlayerView.InPlay(id, you, names: Names, arrangement, …)` and
    `PlayerView.Over(id, you, names: Names, result, previous)`.
  - `GameService.create(name: PlayerName): F[Credentials]` and
    `GameService.join(id: GameId, name: PlayerName): F[Either[GameFault, Credentials]]`.
  - `wiggly.gin.protocol.Introduction(name: PlayerName)` with an encoder and a decoder.
  - `400 {"error":"malformed-name"}` from `POST /games` and `POST /games/{id}/join`.

- [ ] **Step 1: Write the failing domain tests**

In `GameSuite`, add the names beside the tokens and use them in `waiting`, `joined` and `knocked`:

```scala
  private val hostName  = NameGen.named("Ada")
  private val guestName = NameGen.named("Grace")

  private val waiting = Game.AwaitingOpponent(id, host, hostName)

  private def joined(deck: Deck = Deck.ordered): Game =
    waiting
      .joined(guest, guestName, deck)
      .getOrElse(sys.error("a waiting game refused an opponent"))
```

In `knocked`, the join becomes `waiting.joined(guest, guestName, deal.deck)`. The test "a second
opponent is turned away" becomes
`joined().joined(Token("third"), NameGen.named("Third"), Deck.ordered)`. Import
`wiggly.gin.gen.NameGen`. Then add:

```scala
  pureTest("joining records the host's name and then the guest's") {
    expect.eql(inPlay(joined()).names, Names(hostName, guestName))
  }

  pureTest("a scored round carries both names into the next deal") {
    val next = Deck.from(Deck.ordered.cards.reverse).getOrElse(sys.error("not a deck"))

    expect.eql(knocked(gin, worthTen, next).map(inPlay(_).names), Right(Names(hostName, guestName)))
  }
```

In `PlayerViewSuite`, the waiting game gains a name, and the first test expects it:

```scala
  private val waiting = Game.AwaitingOpponent(GameId("a-game"), Token("host"), NameGen.named("Ada"))

  pureTest("a game waiting for an opponent says only that, and whose it is") {
    expect.eql(
      PlayerView.of(waiting, Player.One),
      PlayerView.AwaitingOpponent(GameId("a-game"), Player.One, NameGen.named("Ada"))
    )
  }

  test("every view of a round in play carries both names unchanged") {
    forall(GameGen.gamePlayed(10)) { game =>
      game match {
        case playing: Game.InPlay =>
          Player.values.toList.foldLeft(success) { (soFar, you) =>
            soFar and expect.eql(inPlay(PlayerView.of(playing, you)).names, playing.names)
          }
        case _ => success
      }
    }
  }
```

- [ ] **Step 2: Update the shared generators and the fixtures**

In `GameGen.gamePlayed` and `GameGen.inPlay`, draw two names and pass them:

```scala
    hostName  <- NameGen.name
    guestName <- NameGen.name
```

`Game.AwaitingOpponent(id, host)` becomes `Game.AwaitingOpponent(id, host, hostName)`, and
`.joined(guest, first)` becomes `.joined(guest, guestName, first)`, in both generators.

The other fixtures change as follows. Each one imports `wiggly.gin.gen.NameGen`.

| File | Before | After |
| --- | --- | --- |
| `MovesSuite.scala` | `tokens = Tokens(Token("t1"), Token("t2")),` | the same line, then `names = Names(NameGen.named("Ada"), NameGen.named("Grace")),` |
| `MemoryGameEventsSuite.scala` | `Game.AwaitingOpponent(gameId, Token(host))` | `Game.AwaitingOpponent(gameId, Token(host), NameGen.named("Ada"))` |
| `MemoryGameRepositorySuite.scala` | `Game.AwaitingOpponent(id, host)` | `Game.AwaitingOpponent(id, host, NameGen.named("Ada"))` |
| `MemoryGameRepositorySuite.scala` | `_.joined(Token("guest"), Deck.ordered)` | `_.joined(Token("guest"), NameGen.named("Grace"), Deck.ordered)` |
| `MemoryGameRepositorySuite.scala` | `_.joined(Token(s"guest-$attempt"), Deck.ordered)` | `_.joined(Token(s"guest-$attempt"), NameGen.named("Grace"), Deck.ordered)` |
| `UpdateSuite.scala`, `ViewSuite.scala` | `new PlayerView.AwaitingOpponent(GameId("g1"), Player.One)` | `new PlayerView.AwaitingOpponent(GameId("g1"), Player.One, NameGen.named("Ada"))` |

- [ ] **Step 3: Write the failing service tests**

In `GamesSuite`, add the names and pass them wherever the suite creates or joins:

```scala
  private val ada   = NameGen.named("Ada")
  private val grace = NameGen.named("Grace")
```

Every `table.service.create` becomes `table.service.create(ada)`. Every
`table.service.join(id)` becomes `table.service.join(id, grace)`. The first test expects
`Some(Game.AwaitingOpponent(host.id, host.token, ada))`. Then add:

```scala
  test("both players see the same two names, the host's first") {
    for {
      (table, host, guest) <- seated()
      theirs               <- table.service.look(host.id, host.token)
      others               <- table.service.look(guest.id, guest.token)
    } yield expect.eql(theirs.map(inPlay(_).names), Right(Names(ada, grace))) and
      expect.eql(others.map(inPlay(_).names), Right(Names(ada, grace)))
  }
```

- [ ] **Step 4: Write the failing codec tests**

In `CodecsSuite`, update the last test's fixture to
`PlayerView.AwaitingOpponent(GameId("a-game"), Player.One, NameGen.named("Ada"))`, and add
`expect.eql(view.hcursor.get[String]("name"), Right("Ada"))` to it. Import `NameGen`. Then add:

```scala
  pureTest("a name is the string it wraps") {
    expect.eql(NameGen.named("Ada").asJson, Json.fromString("Ada"))
  }

  pureTest("a name the rules refuse is refused on the way in, not made into one") {
    expect(Json.fromString("   ").as[PlayerName].isLeft) and
      expect(Json.fromString("x" * 21).as[PlayerName].isLeft)
  }

  test("an introduction survives the trip out and back") {
    forall(NameGen.name) { name =>
      expect.eql(Right(Introduction(name)), Introduction(name).asJson.as[Introduction])
    }
  }
```

- [ ] **Step 5: Write the failing route tests**

In `GameRoutesSuite`, add a body helper and pass names to the service:

```scala
  private def introducing(name: String): Json = Json.obj("name" -> Json.fromString(name))
```

`seated` calls `table.service.create(NameGen.named("Ada"))` and
`table.service.join(host.id, NameGen.named("Grace"))`. The two route tests that create or join
send `.withEntity(introducing("Ada"))` and `.withEntity(introducing("Grace"))`. The 404 and 409
join tests send `.withEntity(introducing("Grace"))` too, so that they still reach the service.
Then add:

```scala
  test("creating a game without a name answers 400 and creates nothing") {
    for {
      table    <- table
      response <- table.app.run(Request[IO](Method.POST, uri"/games"))
      body     <- response.as[Json]
    } yield expect.eql(response.status, Status.BadRequest) and
      expect.eql(body.hcursor.get[String]("error"), Right("malformed-name"))
  }

  test("a name of nothing but space answers 400") {
    for {
      table    <- table
      response <- table.app.run(
        Request[IO](Method.POST, uri"/games").withEntity(introducing("   "))
      )
      body <- response.as[Json]
    } yield expect.eql(response.status, Status.BadRequest) and
      expect.eql(body.hcursor.get[String]("error"), Right("malformed-name"))
  }

  test("joining without a name answers 400 and leaves the seat free") {
    for {
      table    <- table
      host     <- table.service.create(NameGen.named("Ada"))
      response <- table.app.run(Request[IO](Method.POST, at(s"/games/${host.id.value}/join")))
      later    <- table.service.join(host.id, NameGen.named("Grace"))
    } yield expect.eql(response.status, Status.BadRequest) and expect(later.isRight)
  }
```

- [ ] **Step 6: Run the tests to make sure that they fail**

Run: `sbt test`
Expected: compile failures in `core`, naming `Names`, the third argument of `AwaitingOpponent` and
the arity of `joined`.

- [ ] **Step 7: Change the domain**

In `Game.scala`, add `Names` after `Tokens`:

```scala
/** A name for each player, keyed so that a lookup cannot fail. */
final case class Names(one: PlayerName, two: PlayerName) {

  def apply(player: Player): PlayerName = {
    player match {
      case Player.One => one
      case Player.Two => two
    }
  }
}

object Names {
  given Eq[Names] = Eq.fromUniversalEquals

  given Show[Names] = Show.fromToString
}
```

Then change the cases and the functions that build them:

```scala
  final case class AwaitingOpponent(id: GameId, host: Token, hostName: PlayerName) extends Game

  final case class InPlay(
      id: GameId,
      tokens: Tokens,
      names: Names,
      ledger: Match.InProgress,
      round: GameState.InProgress,
      previous: Option[RoundResult]
  ) extends Game

  final case class Over(
      id: GameId,
      tokens: Tokens,
      names: Names,
      ledger: Match.Finished,
      previous: RoundResult
  ) extends Game
```

`holder` matches `case Game.AwaitingOpponent(_, host, _) =>`. `joined` becomes:

```scala
  def joined(guest: Token, guestName: PlayerName, deck: Deck): Either[GameFault, Game] = {
    this match {
      case Game.AwaitingOpponent(id, host, hostName) => {
        val ledger = Match.start(Player.Two)

        Right(
          Game.InPlay(
            id,
            Tokens(host, guest),
            Names(hostName, guestName),
            ledger,
            GameState.deal(deck, ledger.seats),
            None
          )
        )
      }
      case _ => Left(GameFault.AlreadyFull)
    }
  }
```

In `settled`, pass `playing.names` after `playing.tokens` in both the `InPlay` and the `Over` it
builds.

In `PlayerView.scala`, add the fields and fill them in `of`:

```scala
  case AwaitingOpponent(id: GameId, you: Player, name: PlayerName)

  case InPlay(
      id: GameId,
      you: Player,
      names: Names,
      arrangement: Arrangement,
      // the remaining fields are unchanged
  )

  case Over(id: GameId, you: Player, names: Names, result: MatchResult, previous: RoundResult)
```

```scala
      case waiting: Game.AwaitingOpponent => AwaitingOpponent(waiting.id, you, waiting.hostName)
```

In the `InPlay` branch of `of`, add `names = playing.names,` after `you = you,`. The `Over` branch
becomes `Over(over.id, you, over.names, over.ledger.result, over.previous)`.

- [ ] **Step 8: Change the port and the service**

In `GameService.scala`:

```scala
  def create(name: PlayerName): F[Credentials]

  def join(id: GameId, name: PlayerName): F[Either[GameFault, Credentials]]
```

Add `PlayerName` to the import. In `Games.scala`:

```scala
      def create(name: PlayerName): F[Credentials] = {
        for {
          id    <- secrets.gameId
          token <- secrets.token
          game = Game.AwaitingOpponent(id, token, name)
          _ <- repository.create(game)
          _ <- events.publish(game)
        } yield Credentials(id, Player.One, token)
      }

      def join(id: GameId, name: PlayerName): F[Either[GameFault, Credentials]] = {
        for {
          token  <- secrets.token
          deck   <- shuffler.shuffled
          joined <- repository.update(id)(_.joined(token, name, deck))
          _      <- announce(joined)
        } yield joined.as(Credentials(id, Player.Two, token))
      }
```

- [ ] **Step 9: Add the wire format**

Create `Introduction.scala`:

```scala
package wiggly.gin.protocol

import cats.{Eq, Show}
import wiggly.gin.core.domain.PlayerName

/** The body of a request for a seat: who is sitting down. */
final case class Introduction(name: PlayerName)

object Introduction {
  given Eq[Introduction] = Eq.fromUniversalEquals

  given Show[Introduction] = Show.fromToString
}
```

In `Codecs.scala`, after the `GameId` and `Token` wrappers:

```scala
  given Encoder[PlayerName] = Encoder.encodeString.contramap(_.value)

  /** Rebuilt through the smart constructor, for the reason a meld is: decoding is the one door
    * a name that breaks the rule could otherwise walk through.
    */
  given Decoder[PlayerName] =
    Decoder.decodeString.emap(raw => PlayerName.from(raw).toRight(s"not a name: $raw"))

  given Encoder[Names] = Encoder.AsObject.derived
  given Decoder[Names] = Decoder.derived

  given Encoder[Introduction] = Encoder.AsObject.derived
  given Decoder[Introduction] = Decoder.derived
```

- [ ] **Step 10: Read the name in the routes**

In `GameRoutes.scala`, add this beside `withToken`:

```scala
    /** A seat is taken by somebody, so a request that does not say who is answered before the
      * service is asked.
      */
    def introduced(request: Request[F])(seated: PlayerName => F[Response[F]]): F[Response[F]] =
      request.attemptAs[Introduction].value.flatMap {
        case Right(introduction) => seated(introduction.name)
        case Left(_)             =>
          BadRequest(Json.obj("error" -> Json.fromString("malformed-name")))
      }
```

The two routes become:

```scala
      case request @ POST -> Root / "games" =>
        introduced(request) { name =>
          service.create(name).flatMap(credentials => Created(credentials.asJson))
        }

      case request @ POST -> Root / "games" / id / "join" =>
        introduced(request)(name => service.join(GameId(id), name).flatMap(answered))
```

Import `PlayerName` and `wiggly.gin.protocol.Introduction`.

- [ ] **Step 11: Run the tests to make sure that they pass**

Run: `sbt test`
Expected: PASS in every module.

- [ ] **Step 12: Write the API description and the Bruno requests**

In `docs/openapi.yaml`, add a request body to `createGame` and to `joinGame`, after `security: []`:

```yaml
      requestBody:
        $ref: "#/components/requestBodies/Introduction"
```

Add a `400` response to both:

```yaml
        "400":
          description: The body does not hold a name of 1 to 20 characters.
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Error"
              example:
                error: malformed-name
```

Add a `requestBodies` section under `components`, after `parameters`:

```yaml
  requestBodies:
    Introduction:
      required: true
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/Introduction"
```

Add these schemas after `Credentials`:

```yaml
    PlayerName:
      type: string
      minLength: 1
      maxLength: 20
      description: |
        What a player calls themselves. The server trims space from both ends and refuses control
        characters. The limit counts characters, not bytes. A name is a label: two players can
        share one, and the token is still the only claim to a seat.

    Names:
      type: object
      description: The name of each player, keyed by seat.
      properties:
        one:
          $ref: "#/components/schemas/PlayerName"
        two:
          $ref: "#/components/schemas/PlayerName"
      required: [one, two]

    Introduction:
      type: object
      description: Who is taking the seat.
      properties:
        name:
          $ref: "#/components/schemas/PlayerName"
      required: [name]
```

In `PlayerViewAwaitingOpponent`, add `name` with `$ref: "#/components/schemas/PlayerName"` and add
it to `required`. In `PlayerViewInPlay` and `PlayerViewOver`, add `names` with
`$ref: "#/components/schemas/Names"` and add it to `required`. Rewrite the first paragraph of
`info.description`. It says that a player gives a name to create or to join a game.

In `bruno/Seats/Create game.bru`, change `body: none` to `body: json` and add:

```
body:json {
  {
    "name": "Ada"
  }
}
```

Do the same in `bruno/Seats/Join game.bru` with `"name": "Grace"`. Create
`bruno/Refusals/400 Malformed name.bru`:

```
meta {
  name: 400 Malformed name
  type: http
  seq: 6
}

post {
  url: {{baseUrl}}/api/v1/games
  body: json
  auth: none
}

headers {
  Accept: application/json
}

body:json {
  {
    "name": "   "
  }
}

docs {
  A name of nothing but space. The server answers `400` `malformed-name`, and no game is
  created. A missing body gets the same answer.
}
```

Run: `npx --yes @redocly/cli@2 lint docs/openapi.yaml`
Expected: no errors.

- [ ] **Step 13: Commit**

```bash
git add -A modules docs/openapi.yaml bruno
git commit -m "Name both players in the game and on the wire"
```

---

### Task 3: Names in the browser

**Files:**
- Create: `modules/ui/src/main/scala/wiggly/gin/ui/Profile.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Model.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Msg.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Update.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Api.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Main.scala`
- Test: `modules/ui/src/test/scala/wiggly/gin/ui/UpdateSuite.scala`, `ViewSuite.scala`

**Interfaces:**
- Consumes: `PlayerName`, `Names`, the view fields, and `Introduction` from Task 2.
- Produces:
  - `Model.Start(name: String, joining: String, error: Option[String])`.
  - `Msg.NameChanged(name: String)`.
  - `Update.NameWanted: String`.
  - `Api.create(name: PlayerName)` and `Api.join(id: String, name: PlayerName)`.
  - `Profile.remember(name: String): Unit` and `Profile.recall: Option[String]`.

- [ ] **Step 1: Write the failing update tests**

In `UpdateSuite`, every `Model.Start(joining, error)` gains a name in front.
`Model.Start("", None)` becomes `Model.Start("", "", None)`. `Model.Start("g2", None)` becomes
`Model.Start("", "g2", None)`, and the same for its expected value. Then add:

```scala
  pureTest("typing a name is remembered") {
    val (model, _) = Update(Model.Start("", "", None), Msg.NameChanged("Ada"))
    expect.same(Model.Start("Ada", "", None), model)
  }

  test("asking for a seat without a usable name asks for one and sends nothing") {
    val unusable = Gen.oneOf("", "   ", "x" * (PlayerName.MaxLength + 1))
    val asking   = Gen.oneOf(Msg.Create, Msg.Join)

    forall(Gen.zip(unusable, asking)) { (name, msg) =>
      val (model, command) = Update(Model.Start(name, "g2", None), msg)

      expect.same(Model.Start(name, "g2", Some(Update.NameWanted)), model) and
        expect(command == Cmd.None)
    }
  }

  pureTest("asking for a seat with a name clears the last error and sends the request") {
    val (model, command) = Update(Model.Start("Ada", "", Some("stale")), Msg.Create)

    expect.same(Model.Start("Ada", "", None), model) and expect(command != Cmd.None)
  }
```

Import `tyrian.Cmd`.

- [ ] **Step 2: Write the failing view tests**

In `ViewSuite`, change `drawn(Model.Start("", None))` to `drawn(Model.Start("", "", None))`, and
add `expect(rendered.contains("your name"))` to that test. Then add:

```scala
  pureTest("the waiting screen names the player") {
    expect(drawn(Model.Seated(seat, waiting, None)).contains("Ada"))
  }

  test("the scoreboard names both players") {
    forall(ViewGen.inPlay) { view =>
      val rendered = drawn(playing(view))

      expect(rendered.contains(view.names.one.value)) and
        expect(rendered.contains(view.names.two.value))
    }
  }
```

- [ ] **Step 3: Run the tests to make sure that they fail**

Run: `sbt "ui/testOnly wiggly.gin.ui.UpdateSuite wiggly.gin.ui.ViewSuite"`
Expected: compile failure, because `Model.Start` takes two arguments and `Msg.NameChanged` does
not exist.

- [ ] **Step 4: Change the model, the messages and the update**

`Model.Start` becomes `case Start(name: String, joining: String, error: Option[String])`. `Msg`
gains `case NameChanged(name: String)` before `JoiningChanged`.

In `Update.scala`, replace the four `Model.Start` cases with these:

```scala
      case (start: Model.Start, Msg.NameChanged(name)) =>
        (start.copy(name = name), Cmd.None)

      case (start: Model.Start, Msg.JoiningChanged(id)) =>
        (start.copy(joining = id), Cmd.None)

      case (start: Model.Start, Msg.Create) =>
        named(start)(Api.create)

      case (start: Model.Start, Msg.Join) =>
        named(start)(Api.join(start.joining, _))
```

The failure case becomes
`case (start: Model.Start, Msg.Failed(reason)) => (start.copy(error = Some(reason)), Cmd.None)`.
Add these members to the object:

```scala
  val NameWanted: String = s"Type a name of 1 to ${PlayerName.MaxLength} characters."

  /** A seat is asked for only under a name the server accepts, so that a bad one is answered at
    * once rather than after a round trip.
    */
  private def named(start: Model.Start)(ask: PlayerName => Cmd[IO, Msg]): (Model, Cmd[IO, Msg]) =
    PlayerName.from(start.name) match {
      case Some(name) => (start.copy(error = None), ask(name))
      case None       => (start.copy(error = Some(NameWanted)), Cmd.None)
    }
```

Import `wiggly.gin.core.domain.PlayerName`.

- [ ] **Step 5: Send the name**

In `Api.scala`:

```scala
  def create(name: PlayerName): Cmd[IO, Msg] =
    Http.send(Request.post(games, introducing(name)), reading[Credentials](Msg.Took.apply))

  def join(id: String, name: PlayerName): Cmd[IO, Msg] =
    Http.send(
      Request.post(s"$games/$id/join", introducing(name)),
      reading[Credentials](Msg.Took.apply)
    )

  private def introducing(name: PlayerName): Body = Body.json(Introduction(name).asJson.noSpaces)
```

Import `PlayerName` and `wiggly.gin.protocol.Introduction`.

- [ ] **Step 6: Remember the name between visits**

Create `Profile.scala`:

```scala
package wiggly.gin.ui

import org.scalajs.dom

/** The name a player last typed. `localStorage` rather than the `sessionStorage` a seat lives in,
  * because a name outlives a game and a tab.
  */
object Profile {

  val key: String = "wiggly-gin:name"

  def remember(name: String): Unit = dom.window.localStorage.setItem(key, name)

  def recall: Option[String] = Option(dom.window.localStorage.getItem(key))
}
```

In `Main.scala`, `init` builds
`Model.Start(Profile.recall.getOrElse(""), arriving.getOrElse(""), None)`. The `match` in
`update` gains `case Msg.NameChanged(name) => Profile.remember(name)`. The last subscription case
becomes `case Model.Start(_, _, _) => Sub.None`.

- [ ] **Step 7: Draw the names**

In `View.scala`, `apply` matches `case begun: Model.Start => start(begun)`. `start` becomes:

```scala
  private def start(model: Model.Start): Html[Msg] = {
    div(cls := "start")(
      h1(text("Gin Rummy")),
      input(
        value := model.name,
        onInput(name => Msg.NameChanged(name)),
        placeholder := "your name",
        Aria.ariaLabel := "your name"
      ),
      button(onClick(Msg.Create))(text("Create a game")),
      p(text("or join one you were sent:")),
      input(
        value := model.joining,
        onInput(id => Msg.JoiningChanged(id)),
        placeholder := "game id"
      ),
      button(onClick(Msg.Join))(text("Join")),
      problem(model.error)
    )
  }
```

In `awaiting`, add `p(text(s"You are ${view.name.value}."))` after the heading.

`scoreboard` becomes:

```scala
  private def scoreboard(view: PlayerView.InPlay): Html[Msg] = {
    val them = view.names(view.you.other).value
    val turn = if (view.onTurn === view.you) "Your turn" else s"Waiting for $them"

    div(cls := "scoreboard")(
      p(text(s"${view.names(view.you).value} ${view.totals(view.you)}")),
      p(text(s"$them ${view.totals(view.you.other)}")),
      p(cls := "turn")(text(turn))
    )
  }
```

`table` calls `view.previous.fold(empty)(lastRound(view.names, _))`. `lastRound` takes
`(names: Names, result: RoundResult)` and says `names(winner).value` where it said `who(winner)`.
Delete `who`. `finished` becomes:

```scala
  private def finished(view: PlayerView.Over): Html[Msg] = {
    val you  = view.names(view.you).value
    val them = view.names(view.you.other).value
    val said = if (view.result.winner === view.you) "You won the match" else s"$them won the match"

    div(cls := "over")(
      h1(text(said)),
      p(text(s"$you ${view.result.totals(view.you)}, $them ${view.result.totals(view.you.other)}"))
    )
  }
```

- [ ] **Step 8: Run the tests to make sure that they pass**

Run: `sbt ui/test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add modules/ui
git commit -m "Ask for a name and show both at the table"
```

---

### Task 4: The lobby as a value

**Files:**
- Create: `modules/core/src/main/scala/wiggly/gin/core/domain/Lobby.scala`
- Create: `modules/core/src/test/scala/wiggly/gin/gen/LobbyGen.scala`
- Test: `modules/core/src/test/scala/wiggly/gin/core/domain/LobbySuite.scala`

**Interfaces:**
- Consumes: `PlayerName`, `NameGen.named` from Task 1.
- Produces:
  - `final case class ListedAt(millis: Long)`.
  - `final case class Listing(id: GameId, host: PlayerName, since: ListedAt)`.
  - `Lobby.empty: Lobby`, and on `Lobby`: `opened(id: GameId, host: PlayerName, at: ListedAt): Lobby`,
    `closed(id: GameId): Lobby`, `taken(id: GameId): Lobby`, `listings: List[Listing]`.
  - `Eq` and `Show` for all three types.

- [ ] **Step 1: Write the generator**

```scala
package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.domain.{GameId, Lobby, ListedAt}

/** Runs of lobby transitions over three games. Three, so that a run touches each one often enough
  * to open, close and reopen it.
  */
object LobbyGen {

  enum Step {
    case Open(id: GameId, at: ListedAt)
    case Close(id: GameId)
    case Take(id: GameId)
  }

  val ids: List[GameId] = List("g1", "g2", "g3").map(GameId.apply)

  val step: Gen[Step] = Gen.oneOf(ids).flatMap { id =>
    Gen.frequency(
      3 -> Gen.choose(0L, 1000000L).map(at => Step.Open(id, ListedAt(at))),
      2 -> Gen.const(Step.Close(id)),
      1 -> Gen.const(Step.Take(id))
    )
  }

  val steps: Gen[List[Step]] = Gen.choose(0, 30).flatMap(Gen.listOfN(_, step))

  def applied(steps: List[Step]): Lobby =
    steps.foldLeft(Lobby.empty) { (lobby, step) =>
      step match {
        case Step.Open(id, at) => lobby.opened(id, NameGen.named("Ada"), at)
        case Step.Close(id)    => lobby.closed(id)
        case Step.Take(id)     => lobby.taken(id)
      }
    }
}
```

- [ ] **Step 2: Write the failing suite**

```scala
package wiggly.gin.core.domain

import cats.implicits.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.gen.LobbyGen.Step
import wiggly.gin.gen.{LobbyGen, NameGen}

object LobbySuite extends SimpleIOSuite with Checkers {

  private val ada = NameGen.named("Ada")

  /** The same lobby kept the long way, as a count and a time per game. The count goes up for an
    * open, down for a close but never below zero, and to zero for a take. The time is the one
    * from the open that took the count off zero.
    */
  private def expected(steps: List[Step]): List[Listing] = {
    val rows = steps.foldLeft(Map.empty[GameId, (Int, ListedAt)]) { (held, step) =>
      step match {
        case Step.Open(id, at) =>
          held.updated(id, held.get(id).fold((1, at))((count, since) => (count + 1, since)))
        case Step.Close(id) =>
          held.get(id) match {
            case Some((count, since)) if count > 1 => held.updated(id, (count - 1, since))
            case _                                 => held.removed(id)
          }
        case Step.Take(id) => held.removed(id)
      }
    }

    rows.toList
      .map { case (id, (_, since)) => Listing(id, ada, since) }
      .sortBy(listing => (listing.since.millis, listing.id.value))
  }

  test("a game is listed while its opens outweigh its closes, from the open that listed it") {
    forall(LobbyGen.steps) { steps =>
      expect.eql(LobbyGen.applied(steps).listings, expected(steps))
    }
  }

  test("the listings are the oldest first") {
    forall(LobbyGen.steps) { steps =>
      val times = LobbyGen.applied(steps).listings.map(_.since.millis)

      expect.eql(times, times.sorted)
    }
  }

  test("closing or taking a game the lobby does not hold changes nothing") {
    forall(LobbyGen.steps) { steps =>
      val lobby  = LobbyGen.applied(steps)
      val absent = GameId("absent")

      expect.eql(lobby.closed(absent), lobby) and expect.eql(lobby.taken(absent), lobby)
    }
  }

  pureTest("a second tab keeps the game listed when the first one closes") {
    val id    = GameId("g1")
    val twice = Lobby.empty.opened(id, ada, ListedAt(1)).opened(id, ada, ListedAt(2))

    expect.eql(twice.closed(id).listings, List(Listing(id, ada, ListedAt(1)))) and
      expect.eql(twice.closed(id).closed(id).listings, List.empty[Listing])
  }

  pureTest("a take removes the game however many streams hold it") {
    val id    = GameId("g1")
    val twice = Lobby.empty.opened(id, ada, ListedAt(1)).opened(id, ada, ListedAt(2))

    expect.eql(twice.taken(id).listings, List.empty[Listing])
  }
}
```

- [ ] **Step 3: Run it to make sure that it fails**

Run: `sbt "coreJVM/testOnly wiggly.gin.core.domain.LobbySuite"`
Expected: compile failure, `Not found: Lobby`.

- [ ] **Step 4: Write the value**

```scala
package wiggly.gin.core.domain

import cats.{Eq, Show}

/** Epoch milliseconds rather than an `Instant`, because `core` is cross-built and an `Instant` on
  * Scala.js needs a library the build does not carry.
  */
final case class ListedAt(millis: Long)

object ListedAt {
  given Eq[ListedAt] = Eq.fromUniversalEquals

  given Show[ListedAt] = Show.fromToString
}

/** One row of the lobby. `since` is when the host's stream opened, so the waiting time a player
  * reads is how long the host has been there.
  */
final case class Listing(id: GameId, host: PlayerName, since: ListedAt)

object Listing {
  given Eq[Listing] = Eq.fromUniversalEquals

  given Show[Listing] = Show.fromToString
}

/** The games waiting for an opponent whose hosts are watching them.
  *
  * A row counts the streams that hold it, because a host with two tabs, or a refresh whose new
  * stream opens before the old one closes, is still there when one stream goes. Only the
  * transitions below build a lobby, and none of them leaves a row on a count below one.
  */
final case class Lobby private (rows: Map[GameId, Lobby.Row]) {

  def opened(id: GameId, host: PlayerName, at: ListedAt): Lobby = {
    val row = rows.get(id) match {
      case Some(held) => held.copy(watchers = held.watchers + 1)
      case None       => Lobby.Row(Listing(id, host, at), 1)
    }

    Lobby(rows.updated(id, row))
  }

  def closed(id: GameId): Lobby = {
    rows.get(id) match {
      case Some(held) if held.watchers > 1 =>
        Lobby(rows.updated(id, held.copy(watchers = held.watchers - 1)))
      case _ => Lobby(rows.removed(id))
    }
  }

  def taken(id: GameId): Lobby = Lobby(rows.removed(id))

  /** The id breaks a tie, so that two games listed in the same millisecond keep one order. */
  def listings: List[Listing] =
    rows.values.map(_.listing).toList.sortBy(listing => (listing.since.millis, listing.id.value))
}

object Lobby {

  final case class Row(listing: Listing, watchers: Int)

  val empty: Lobby = Lobby(Map.empty)

  given Eq[Lobby] = Eq.fromUniversalEquals

  given Show[Lobby] = Show.fromToString
}
```

- [ ] **Step 5: Run it to make sure that it passes**

Run: `sbt "coreJVM/testOnly wiggly.gin.core.domain.LobbySuite"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/core
git commit -m "Hold the lobby as a value"
```

---

### Task 5: Where the lobby is kept

**Files:**
- Create: `modules/core/src/main/scala/wiggly/gin/core/port/LobbyBoard.scala`
- Create: `modules/core/src/test/scala/wiggly/gin/fake/FakeLobbyBoard.scala`
- Create: `modules/server/src/main/scala/wiggly/gin/server/adapter/memory/MemoryLobbyBoard.scala`
- Test: `modules/server/src/test/scala/wiggly/gin/server/adapter/memory/MemoryLobbyBoardSuite.scala`

**Interfaces:**
- Consumes: `Lobby`, `Listing`, `ListedAt` from Task 4.
- Produces:
  - `trait LobbyBoard[F[_]] { def change(f: Lobby => Lobby): F[Unit]; def watch: Stream[F, List[Listing]] }`.
  - `MemoryLobbyBoard[F[_]: Concurrent]: F[LobbyBoard[F]]`.
  - `FakeLobbyBoard[F[_]: Sync]: F[FakeLobbyBoard[F]]`, with `history: F[NonEmptyList[Lobby]]`.

- [ ] **Step 1: Write the port**

```scala
package wiggly.gin.core.port

import fs2.Stream
import wiggly.gin.core.domain.{Listing, Lobby}

/** Where the lobby is kept.
  *
  * `change` takes a change for the reason `GameRepository.update` does: two streams that open
  * together queue rather than one overwriting the other. `watch` starts with the listings as they
  * stand and then sends each change, so there is no window between asking and listening.
  */
trait LobbyBoard[F[_]] {

  def change(f: Lobby => Lobby): F[Unit]

  def watch: Stream[F, List[Listing]]
}
```

- [ ] **Step 2: Write the fake**

```scala
package wiggly.gin.fake

import cats.Functor
import cats.data.NonEmptyList
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import fs2.Stream
import wiggly.gin.core.domain.{Listing, Lobby}
import wiggly.gin.core.port.LobbyBoard

/** A board that keeps every lobby it has held, so that a test can ask whether a game was listed
  * at any point and not only at the end. `watch` reads the latest once and does not stay open.
  */
final class FakeLobbyBoard[F[_]: Functor](state: Ref[F, NonEmptyList[Lobby]])
    extends LobbyBoard[F] {

  def change(f: Lobby => Lobby): F[Unit] = state.update(held => held :+ f(held.last))

  def watch: Stream[F, List[Listing]] = Stream.eval(state.get).map(_.last.listings)

  def history: F[NonEmptyList[Lobby]] = state.get
}

object FakeLobbyBoard {
  def apply[F[_]: Sync]: F[FakeLobbyBoard[F]] =
    Ref.of(NonEmptyList.one(Lobby.empty)).map(new FakeLobbyBoard(_))
}
```

- [ ] **Step 3: Write the failing adapter suite**

```scala
package wiggly.gin.server.adapter.memory

import cats.effect.IO
import cats.implicits.*
import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{GameId, ListedAt, Listing}
import wiggly.gin.gen.NameGen

object MemoryLobbyBoardSuite extends SimpleIOSuite {

  private val ada     = NameGen.named("Ada")
  private val id      = GameId("g1")
  private val listing = Listing(id, ada, ListedAt(1))

  test("a watcher is handed the lobby as it stands before anything else happens") {
    for {
      board <- MemoryLobbyBoard[IO]
      _     <- board.change(_.opened(id, ada, ListedAt(1)))
      seen  <- board.watch.take(1).compile.toList
    } yield expect.eql(seen, List(List(listing)))
  }

  /** The take happens after the first lobby arrived, which proves that the watcher was already
    * listening. The second open in between leaves the listings as they were, so it sends nothing.
    */
  test("a watcher hears each change to the listings and nothing that leaves them alone") {
    for {
      board <- MemoryLobbyBoard[IO]
      _     <- board.change(_.opened(id, ada, ListedAt(1)))
      seen  <- board.watch
        .take(2)
        .zipWithIndex
        .evalMap { (listings, index) =>
          IO.whenA(index === 0L) {
            board.change(_.opened(id, ada, ListedAt(2))) >> board.change(_.taken(id))
          }.as(listings)
        }
        .compile
        .toList
    } yield expect.eql(seen, List(List(listing), List.empty[Listing]))
  }

  test("changes made at once are all kept") {
    for {
      board <- MemoryLobbyBoard[IO]
      _     <- (1 to 100).toList.parTraverse_ { n =>
        board.change(_.opened(GameId(s"g$n"), ada, ListedAt(n.toLong)))
      }
      seen <- board.watch.take(1).compile.lastOrError
    } yield expect.eql(seen.size, 100)
  }
}
```

- [ ] **Step 4: Run it to make sure that it fails**

Run: `sbt "server/testOnly wiggly.gin.server.adapter.memory.MemoryLobbyBoardSuite"`
Expected: compile failure, `Not found: MemoryLobbyBoard`.

- [ ] **Step 5: Write the adapter**

```scala
package wiggly.gin.server.adapter.memory

import cats.effect.Concurrent
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import wiggly.gin.core.domain.{Listing, Lobby}
import wiggly.gin.core.port.LobbyBoard

/** The lobby in one signal, for the reasons `MemoryGameEvents` uses one per game: a watcher that
  * falls behind wants the list as it now is, and nobody waits for a slow one.
  *
  * `changes` drops a lobby whose listings did not move, such as a second tab opening on a game
  * already listed, so that a watcher is not sent the same list twice.
  */
object MemoryLobbyBoard {

  def apply[F[_]: Concurrent]: F[LobbyBoard[F]] =
    SignallingRef.of[F, Lobby](Lobby.empty).map(from)

  private def from[F[_]: Concurrent](lobby: SignallingRef[F, Lobby]): LobbyBoard[F] = {
    new LobbyBoard[F] {

      def change(f: Lobby => Lobby): F[Unit] = lobby.update(f)

      def watch: Stream[F, List[Listing]] = lobby.discrete.map(_.listings).changes
    }
  }
}
```

- [ ] **Step 6: Run it to make sure that it passes**

Run: `sbt "server/testOnly wiggly.gin.server.adapter.memory.MemoryLobbyBoardSuite"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/core modules/server
git commit -m "Keep the lobby on a board"
```

---

### Task 6: List a game while its host watches it

**Files:**
- Modify: `modules/core/src/main/scala/wiggly/gin/core/service/Games.scala`
- Create: `modules/core/src/main/scala/wiggly/gin/core/port/LobbyService.scala`
- Create: `modules/core/src/main/scala/wiggly/gin/core/service/Lobbies.scala`
- Modify: `modules/server/src/main/scala/wiggly/gin/server/Main.scala`
- Modify (call sites): `modules/server/src/test/scala/wiggly/gin/server/adapter/http/GameRoutesSuite.scala`
- Test: `modules/core/src/test/scala/wiggly/gin/core/service/GamesSuite.scala`
- Test: `modules/server/src/test/scala/wiggly/gin/server/LobbyPresenceSuite.scala`

**Interfaces:**
- Consumes: `LobbyBoard`, `FakeLobbyBoard`, `MemoryLobbyBoard` from Task 5. `Lobby` and `ListedAt`
  from Task 4.
- Produces:
  - `Games[F[_]: Monad: Clock](repository, events, board: LobbyBoard[F], shuffler, secrets): GameService[F]`.
  - `trait LobbyService[F[_]] { def listings: Stream[F, List[Listing]] }`.
  - `Lobbies[F[_]](board: LobbyBoard[F]): LobbyService[F]`.

- [ ] **Step 1: Write the failing service tests**

In `GamesSuite`, the `Table` gains `board: FakeLobbyBoard[IO]`. `table` builds it with
`board <- FakeLobbyBoard[IO]` and passes it to
`Games[IO](repository, events, board, new FixedShuffler[IO](deck), secrets)`. Then add:

```scala
  test("a host watching a waiting game lists it while the stream is open, and not after") {
    for {
      table   <- table()
      host    <- table.service.create(ada)
      watched <- table.service.watch(host.id, host.token)
      _       <- watched.toOption.traverse_(_.compile.drain)
      history <- table.board.history
    } yield expect(history.exists(_.listings.map(l => (l.id, l.host)) == List((host.id, ada)))) and
      expect.eql(history.last.listings, List.empty[Listing])
  }

  test("creating, joining and a guest's watch leave the lobby alone") {
    for {
      (table, _, guest) <- seated()
      watched           <- table.service.watch(guest.id, guest.token)
      _                 <- watched.toOption.traverse_(_.compile.drain)
      history           <- table.board.history
    } yield expect.eql(history.size, 1)
  }

  test("a host watching a game already in play does not list it") {
    for {
      (table, host, _) <- seated()
      watched          <- table.service.watch(host.id, host.token)
      _                <- watched.toOption.traverse_(_.compile.drain)
      history          <- table.board.history
    } yield expect.eql(history.size, 1)
  }

  test("the lobby a client reads is the board's") {
    for {
      board    <- FakeLobbyBoard[IO]
      _        <- board.change(_.opened(GameId("g1"), ada, ListedAt(1)))
      listings <- Lobbies[IO](board).listings.compile.lastOrError
    } yield expect.eql(listings, List(Listing(GameId("g1"), ada, ListedAt(1))))
  }
```

Import `wiggly.gin.fake.FakeLobbyBoard`.

- [ ] **Step 2: Write the failing presence suite**

This suite runs the service against the memory adapters, because the presence rule needs a live
event stream.

```scala
package wiggly.gin.server

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.implicits.*
import fs2.Stream
import weaver.{Expectations, SimpleIOSuite}
import wiggly.gin.core.domain.*
import wiggly.gin.core.port.{Credentials, GameRepository, GameService, LobbyBoard}
import wiggly.gin.core.service.Games
import wiggly.gin.fake.{CountingSecrets, FixedShuffler}
import wiggly.gin.gen.NameGen
import wiggly.gin.server.adapter.memory.{MemoryGameEvents, MemoryGameRepository, MemoryLobbyBoard}

import scala.concurrent.duration.*

object LobbyPresenceSuite extends SimpleIOSuite {

  private val ada   = NameGen.named("Ada")
  private val grace = NameGen.named("Grace")

  private final case class Table(
      service: GameService[IO],
      repository: GameRepository[IO],
      board: LobbyBoard[IO]
  )

  private val table: IO[Table] = for {
    repository <- MemoryGameRepository[IO]
    events     <- MemoryGameEvents[IO]
    board      <- MemoryLobbyBoard[IO]
    secrets    <- CountingSecrets[IO]
    service = Games[IO](repository, events, board, new FixedShuffler[IO](Deck.ordered), secrets)
  } yield Table(service, repository, board)

  private def watching(table: Table, seat: Credentials): IO[Stream[IO, PlayerView]] =
    table.service.watch(seat.id, seat.token).flatMap {
      case Right(views) => IO.pure(views)
      case Left(fault)  => IO.raiseError(new IllegalStateException(s"refused: $fault"))
    }

  private def now(table: Table): IO[List[Listing]] = table.board.watch.take(1).compile.lastOrError

  /** Waits for the lobby to read as asked, so that a test never races the fiber that changes it. */
  private def until(table: Table)(wanted: List[Listing] => Boolean): IO[List[Listing]] =
    table.board.watch.find(wanted).compile.lastOrError.timeout(5.seconds)

  /** The lobby as the stream's first view arrives. The tap that takes a row down runs before the
    * view does, so this reads the lobby after the stream decided about it.
    */
  private def onFirstView(table: Table, views: Stream[IO, PlayerView]): IO[List[Listing]] =
    views.evalMap(_ => now(table)).take(1).compile.lastOrError.timeout(5.seconds)

  /** The one rule that spans both stores: every listed game is one the store holds as waiting. */
  private def agrees(table: Table): IO[Expectations] = for {
    listings <- now(table)
    stored   <- listings.traverse(listing => table.repository.read(listing.id))
  } yield expect(stored.forall {
    case Some(_: Game.AwaitingOpponent) => true
    case _                              => false
  })

  test("a host who watches lists the game, and the row goes when the stream does") {
    for {
      table  <- table
      host   <- table.service.create(ada)
      views  <- watching(table, host)
      fiber  <- views.compile.drain.start
      listed <- until(table)(_.nonEmpty)
      during <- agrees(table)
      _      <- fiber.cancel
      after  <- now(table)
    } yield expect.eql(listed.map(l => (l.id, l.host)), List((host.id, ada))) and
      during and
      expect.eql(after, List.empty[Listing])
  }

  test("two streams from the host keep the row until both are gone") {
    for {
      table  <- table
      host   <- table.service.create(ada)
      first  <- watching(table, host).flatMap(_.compile.drain.start)
      second <- watching(table, host).flatMap(_.compile.drain.start)
      _      <- until(table)(_.nonEmpty)
      _      <- first.cancel
      one    <- now(table)
      _      <- second.cancel
      none   <- now(table)
    } yield expect.eql(one.map(_.id), List(host.id)) and expect.eql(none, List.empty[Listing])
  }

  test("a join takes the row down while the host is still watching") {
    for {
      table <- table
      host  <- table.service.create(ada)
      fiber <- watching(table, host).flatMap(_.compile.drain.start)
      _     <- until(table)(_.nonEmpty)
      _     <- table.service.join(host.id, grace)
      after <- until(table)(_.isEmpty)
      check <- agrees(table)
      _     <- fiber.cancel
    } yield expect.eql(after, List.empty[Listing]) and check
  }

  test("a join that lands between the host's look and the listing leaves no row behind") {
    for {
      table <- table
      host  <- table.service.create(ada)
      views <- watching(table, host)
      _     <- table.service.join(host.id, grace)
      seen  <- onFirstView(table, views)
    } yield expect.eql(seen, List.empty[Listing])
  }

  test("a guest's stream lists nothing") {
    for {
      table <- table
      host  <- table.service.create(ada)
      guest <- table.service
        .join(host.id, grace)
        .flatMap(joined => IO.fromOption(joined.toOption)(new IllegalStateException("refused")))
      views <- watching(table, guest)
      seen  <- onFirstView(table, views)
    } yield expect.eql(seen, List.empty[Listing])
  }

  test("a row carries the time the host's stream opened") {
    TestControl.executeEmbed {
      for {
        table <- table
        host  <- table.service.create(ada)
        _     <- IO.sleep(5.minutes)
        views <- watching(table, host)
        seen  <- views.evalMap(_ => now(table)).take(1).compile.lastOrError
      } yield expect.eql(seen.map(_.since), List(ListedAt(5.minutes.toMillis)))
    }
  }
}
```

`TestControl` comes from `cats-effect-testkit`. Add it to `testSettings` in `build.sbt`:

```scala
    "org.typelevel"  %%% "cats-effect-testkit" % catsEffectVersion % Test,
```

- [ ] **Step 3: Update the other call sites**

In `GameRoutesSuite.table`, add `board <- MemoryLobbyBoard[IO]` and pass it as the third argument
to `Games`. Import `wiggly.gin.server.adapter.memory.MemoryLobbyBoard`.

In `Main.scala`, add `board <- MemoryLobbyBoard[IO]` to `gameService` and pass it as the third
argument to `Games`. The comment above it names two adapters that a persistent store replaces.
Make it name three.

- [ ] **Step 4: Run the tests to make sure that they fail**

Run: `sbt coreJVM/test server/test`
Expected: compile failure, because `Games` takes four arguments and `Lobbies` does not exist.

- [ ] **Step 5: Add the lobby service**

```scala
package wiggly.gin.core.port

import fs2.Stream
import wiggly.gin.core.domain.Listing

/** The lobby as a client reads it.
  *
  * Separate from [[GameService]], because every call there is about one game and takes a token,
  * and the lobby is about every game and takes none.
  */
trait LobbyService[F[_]] {

  def listings: Stream[F, List[Listing]]
}
```

```scala
package wiggly.gin.core.service

import fs2.Stream
import wiggly.gin.core.domain.Listing
import wiggly.gin.core.port.{LobbyBoard, LobbyService}

object Lobbies {

  def apply[F[_]](board: LobbyBoard[F]): LobbyService[F] = {
    new LobbyService[F] {

      def listings: Stream[F, List[Listing]] = board.watch
    }
  }
}
```

- [ ] **Step 6: Apply the presence rule in `watch`**

In `Games.scala`, the signature becomes:

```scala
  def apply[F[_]: Monad: Clock](
      repository: GameRepository[F],
      events: GameEvents[F],
      board: LobbyBoard[F],
      shuffler: Shuffler[F],
      secrets: Secrets[F]
  ): GameService[F] = {
```

`watch` becomes:

```scala
      def watch(id: GameId, token: Token): F[Either[GameFault, Stream[F, PlayerView]]] =
        seated(id, token).map(_.map { (game, you) =>
          val games = game match {
            case waiting: Game.AwaitingOpponent => listed(waiting, events.watch(id))
            case _                              => events.watch(id)
          }

          games.map(PlayerView.of(_, you))
        })

      /** The host's stream, holding the game in the lobby while it is open.
        *
        * Only the host can be seated at a waiting game, so this is the host's stream without
        * asking. A join can land before the row is made. The stream then opens on the game in
        * play, and the tap takes the row straight back down.
        */
      private def listed(waiting: Game.AwaitingOpponent, games: Stream[F, Game]): Stream[F, Game] = {
        val open = Clock[F].realTime.flatMap { now =>
          board.change(_.opened(waiting.id, waiting.hostName, ListedAt(now.toMillis)))
        }

        Stream.bracket(open)(_ => board.change(_.closed(waiting.id))) >>
          games.evalTap {
            case _: Game.AwaitingOpponent => Monad[F].unit
            case _                        => board.change(_.taken(waiting.id))
          }
      }
```

Import `cats.effect.kernel.Clock`. Update the scaladoc of `Games`, because it now drives the
lobby as well as the game.

- [ ] **Step 7: Run the tests to make sure that they pass**

Run: `sbt test`
Expected: PASS in every module.

- [ ] **Step 8: Commit**

```bash
git add build.sbt modules
git commit -m "List a waiting game while its host watches it"
```

---

### Task 7: Serve the lobby

**Files:**
- Create: `modules/server/src/main/scala/wiggly/gin/server/adapter/http/EventStreams.scala`
- Create: `modules/server/src/main/scala/wiggly/gin/server/adapter/http/LobbyRoutes.scala`
- Modify: `modules/server/src/main/scala/wiggly/gin/server/adapter/http/GameRoutes.scala`
- Modify: `modules/server/src/main/scala/wiggly/gin/server/Main.scala`
- Modify: `modules/protocol/src/main/scala/wiggly/gin/protocol/Codecs.scala`
- Test: `modules/server/src/test/scala/wiggly/gin/server/adapter/http/LobbyRoutesSuite.scala`
- Test: `modules/protocol/src/test/scala/wiggly/gin/protocol/CodecsSuite.scala`
- Modify: `docs/openapi.yaml`
- Create: `bruno/Lobby/folder.bru`, `bruno/Lobby/Watch lobby.bru`

**Interfaces:**
- Consumes: `LobbyService`, `Lobbies` from Task 6. `MemoryLobbyBoard` from Task 5.
- Produces:
  - `EventStreams[F[_]: Temporal, A: Encoder](values: Stream[F, A], heartbeat: FiniteDuration): Stream[F, ServerSentEvent]`.
  - `LobbyRoutes[F[_]: Temporal](lobby: LobbyService[F], heartbeat: FiniteDuration): HttpRoutes[F]`.
  - `GET /api/v1/lobby/events`.
  - Codecs for `ListedAt` (a number) and `Listing` (derived).

- [ ] **Step 1: Write the failing codec tests**

In `CodecsSuite`:

```scala
  pureTest("a listing is its game, its host's name and when it was listed") {
    val listing = Listing(GameId("g1"), NameGen.named("Ada"), ListedAt(1760000000000L))

    expect.eql(
      listing.asJson,
      Json.obj(
        "id"    -> Json.fromString("g1"),
        "host"  -> Json.fromString("Ada"),
        "since" -> Json.fromLong(1760000000000L)
      )
    )
  }

  test("a listing survives the trip out and back") {
    val listings = for {
      id    <- GameGen.gameId
      host  <- NameGen.name
      since <- Gen.choose(0L, 4000000000000L)
    } yield Listing(id, host, ListedAt(since))

    forall(listings)(listing => expect.eql(Right(listing), listing.asJson.as[Listing]))
  }
```

Import `org.scalacheck.Gen`.

- [ ] **Step 2: Write the failing route suite**

```scala
package wiggly.gin.server.adapter.http

import cats.effect.IO
import cats.implicits.*
import org.http4s.implicits.uri
import org.http4s.{Method, Request, Status}
import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{GameId, ListedAt}
import wiggly.gin.core.service.Lobbies
import wiggly.gin.gen.NameGen
import wiggly.gin.server.adapter.memory.MemoryLobbyBoard

import scala.concurrent.duration.*

object LobbyRoutesSuite extends SimpleIOSuite {

  test("the lobby stream opens with the games waiting, and needs no token") {
    for {
      board    <- MemoryLobbyBoard[IO]
      _        <- board.change(_.opened(GameId("g1"), NameGen.named("Ada"), ListedAt(0)))
      app = LobbyRoutes[IO](Lobbies[IO](board), 15.seconds).orNotFound
      response <- app.run(Request[IO](Method.GET, uri"/lobby/events"))
      opening  <- response.body
        .through(fs2.text.utf8.decode)
        .take(1)
        .compile
        .string
        .timeout(5.seconds)
    } yield expect.eql(response.status, Status.Ok) and
      expect(response.contentType.exists(_.mediaType.subType === "event-stream")) and
      expect(opening.contains("\"id\":\"g1\"")) and
      expect(opening.contains("\"host\":\"Ada\""))
  }

  test("an idle lobby stream sends a heartbeat") {
    for {
      board    <- MemoryLobbyBoard[IO]
      app = LobbyRoutes[IO](Lobbies[IO](board), 50.millis).orNotFound
      response <- app.run(Request[IO](Method.GET, uri"/lobby/events"))
      beat     <- response.body
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .find(_.startsWith(":"))
        .compile
        .lastOrError
        .timeout(5.seconds)
    } yield expect(beat.contains("still here"))
  }
}
```

- [ ] **Step 3: Run the tests to make sure that they fail**

Run: `sbt protocolJVM/test "server/testOnly wiggly.gin.server.adapter.http.LobbyRoutesSuite"`
Expected: compile failures, naming the missing `Encoder[Listing]` and `LobbyRoutes`.

- [ ] **Step 4: Add the codecs**

In `Codecs.scala`, after the `Introduction` codecs:

```scala
  /** The number of milliseconds, which is what a browser's `Date` is built from. */
  given Encoder[ListedAt] = Encoder.encodeLong.contramap(_.millis)
  given Decoder[ListedAt] = Decoder.decodeLong.map(ListedAt.apply)

  given Encoder[Listing] = Encoder.AsObject.derived
  given Decoder[Listing] = Decoder.derived
```

In the object's scaladoc, the sentence about encoders that leave the building names `PlayerView`,
`Credentials` and `Listing`.

- [ ] **Step 5: Share the event stream code**

Create `EventStreams.scala`:

```scala
package wiggly.gin.server.adapter.http

import cats.effect.Temporal
import fs2.Stream
import io.circe.Encoder
import io.circe.syntax.*
import org.http4s.ServerSentEvent

import scala.concurrent.duration.FiniteDuration

/** Values as server-sent events, one whole value to an event.
  *
  * The comments are there because an idle connection through a proxy is dropped, and a dropped
  * connection looks to a player like a game or a lobby that stopped responding.
  */
object EventStreams {

  def apply[F[_]: Temporal, A: Encoder](
      values: Stream[F, A],
      heartbeat: FiniteDuration
  ): Stream[F, ServerSentEvent] = {
    val events = values.map(value => ServerSentEvent(data = Some(value.asJson.noSpaces)))
    val beats  = Stream
      .awakeEvery[F](heartbeat)
      .as(ServerSentEvent(data = None, comment = Some("still here")))

    events.merge(beats)
  }
}
```

In `GameRoutes.scala`, delete `watching` and its comment. The events route answers
`case Right(views) => Ok(EventStreams(views, heartbeat))`. Remove the imports that only
`watching` used.

- [ ] **Step 6: Add the route**

```scala
package wiggly.gin.server.adapter.http

import cats.effect.Temporal
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import wiggly.gin.core.port.LobbyService

import scala.concurrent.duration.FiniteDuration

import wiggly.gin.protocol.Codecs.given

/** The lobby, which anybody can watch. It names games and hosts and nothing that claims a seat,
  * so it asks for no token.
  */
object LobbyRoutes {

  def apply[F[_]: Temporal](lobby: LobbyService[F], heartbeat: FiniteDuration): HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl.*

    HttpRoutes.of[F] { case GET -> Root / "lobby" / "events" =>
      Ok(EventStreams(lobby.listings, heartbeat))
    }
  }
}
```

In `Main.scala`, `gameService` becomes `services`, which yields
`(Games[IO](repository, events, board, shuffler, secrets), Lobbies[IO](board))`. The routes become
`GameRoutes[IO](service, config.http.eventHeartbeat) <+> LobbyRoutes[IO](lobby, config.http.eventHeartbeat)`.
Import `cats.syntax.semigroupk.*`, `LobbyRoutes`, `Lobbies` and `LobbyService`.

- [ ] **Step 7: Run the tests to make sure that they pass**

Run: `sbt test`
Expected: PASS in every module.

- [ ] **Step 8: Describe the route**

In `docs/openapi.yaml`, add a `lobby` tag after `games`:

```yaml
  - name: lobby
    description: The games waiting for an opponent.
```

Add the path after `/api/v1/games/{id}/events`:

```yaml
  /api/v1/lobby/events:
    get:
      tags: [lobby]
      operationId: watchLobby
      summary: Stream the games waiting for an opponent
      security: []
      description: |
        The stream opens with the list as it stands, and then sends the whole list again every
        time it changes. A game is listed while its host holds the game's own event stream open
        and nobody joined it. The list is oldest first.
      responses:
        "200":
          description: |
            An open event stream. Each event carries a JSON array of `Listing` on its `data:`
            line. An idle stream sends a comment line every GIN_HTTP_EVENT_HEARTBEAT.
          content:
            text/event-stream:
              schema:
                type: string
```

Add the schema after `Introduction`:

```yaml
    Listing:
      type: object
      description: One game in the lobby.
      properties:
        id:
          type: string
        host:
          $ref: "#/components/schemas/PlayerName"
        since:
          type: integer
          format: int64
          description: When the host's stream opened, in milliseconds since the Unix epoch.
      required: [id, host, since]
```

Make the `text/event-stream` content of the new path match the shape that `watchGame` uses.

Create `bruno/Lobby/folder.bru`:

```
meta {
  name: Lobby
  seq: 6
}

docs {
  The games waiting for an opponent. No token is needed.

  A game shows here only while its host holds the game's own event stream open. Send
  Create game, then hold Watch events open for the host in curl, and the game appears.
}
```

Create `bruno/Lobby/Watch lobby.bru`:

```
meta {
  name: Watch lobby
  type: http
  seq: 1
}

get {
  url: {{baseUrl}}/api/v1/lobby/events
  body: none
  auth: none
}

headers {
  Accept: text/event-stream
}

docs {
  Hold the lobby stream open. Each event is the whole list.

  Bruno collects the whole body before it shows anything, so use curl to watch it:

      curl -N "localhost:8080/api/v1/lobby/events"
}
```

Run: `npx --yes @redocly/cli@2 lint docs/openapi.yaml`
Expected: no errors.

- [ ] **Step 9: Commit**

```bash
git add modules docs/openapi.yaml bruno
git commit -m "Serve the lobby as an event stream"
```

---

### Task 8: The lobby in the browser

**Files:**
- Create: `modules/ui/src/main/scala/wiggly/gin/ui/Waiting.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Model.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Msg.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Update.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Events.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Main.scala`
- Modify: `modules/server/src/main/resources/web/gin.css`
- Test: `modules/ui/src/test/scala/wiggly/gin/ui/WaitingSuite.scala`, `UpdateSuite.scala`,
  `ViewSuite.scala`

**Interfaces:**
- Consumes: `Listing`, `ListedAt` and their codecs from Tasks 4 and 7. `Model.Start` and
  `Update.named` from Task 3.
- Produces:
  - `Model.Start(name: String, joining: String, lobby: List[Listing], now: Long, error: Option[String])`.
  - `Msg.Listed(listings: List[Listing])`, `Msg.Tick(now: Long)`, `Msg.JoinListed(id: GameId)`.
  - `Waiting.age(since: ListedAt, now: Long): String`.
  - `Events.lobby: Sub[IO, Msg]`.

- [ ] **Step 1: Write the failing age suite**

```scala
package wiggly.gin.ui

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.ListedAt

object WaitingSuite extends SimpleIOSuite with Checkers {

  pureTest("a game listed a moment ago has waited under a minute") {
    expect.same("waiting under a minute", Waiting.age(ListedAt(0), 59999))
  }

  pureTest("whole minutes are counted down, not rounded") {
    expect.same("waiting 1 min", Waiting.age(ListedAt(0), 60000)) and
      expect.same("waiting 3 min", Waiting.age(ListedAt(0), 239999))
  }

  /** The time comes from the server and the clock from the browser, and the two can disagree. */
  test("a listing from the future has waited under a minute, not a negative time") {
    forall(Gen.choose(1L, 10000000L)) { ahead =>
      expect.same("waiting under a minute", Waiting.age(ListedAt(ahead), 0))
    }
  }
}
```

- [ ] **Step 2: Write the failing update and view tests**

In `UpdateSuite` and `ViewSuite`, every `Model.Start(name, joining, error)` becomes
`Model.Start(name, joining, Nil, 0, error)`. Then add to `UpdateSuite`:

```scala
  private val listing = Listing(GameId("g2"), NameGen.named("Grace"), ListedAt(0))

  pureTest("a new list replaces the lobby") {
    val (model, _) = Update(Model.Start("Ada", "", Nil, 0, None), Msg.Listed(List(listing)))
    expect.same(Model.Start("Ada", "", List(listing), 0, None), model)
  }

  pureTest("a tick moves the clock the waiting times are read against") {
    val (model, _) = Update(Model.Start("Ada", "", Nil, 0, None), Msg.Tick(60000))
    expect.same(Model.Start("Ada", "", Nil, 60000, None), model)
  }

  pureTest("joining from the lobby without a name asks for one and sends nothing") {
    val (model, command) =
      Update(Model.Start("", "", List(listing), 0, None), Msg.JoinListed(listing.id))

    expect.same(Model.Start("", "", List(listing), 0, Some(Update.NameWanted)), model) and
      expect(command == Cmd.None)
  }

  pureTest("joining from the lobby with a name sends the request") {
    val (_, command) =
      Update(Model.Start("Ada", "", List(listing), 0, None), Msg.JoinListed(listing.id))

    expect(command != Cmd.None)
  }
```

Add to `ViewSuite`:

```scala
  test("every game in the lobby shows its host, how long it waited, and a way to join it") {
    val listings = Gen.listOf(for {
      id   <- GameGen.gameId
      host <- NameGen.name
    } yield Listing(id, host, ListedAt(0)))

    forall(listings) { lobby =>
      val rendered = drawn(Model.Start("Ada", "", lobby, 120000, None))
      val joins    = "Join ".r.findAllIn(rendered).size

      expect(lobby.forall(listing => rendered.contains(listing.host.value))) and
        expect(lobby.isEmpty || rendered.contains("waiting 2 min")) and
        expect.same(lobby.size, joins)
    }
  }

  pureTest("an empty lobby says how to fill it") {
    expect(drawn(Model.Start("Ada", "", Nil, 0, None)).contains("Nobody is waiting"))
  }

  pureTest("the waiting screen says that the game is in the lobby") {
    expect(drawn(Model.Seated(seat, waiting, None)).contains("in the lobby"))
  }
```

`ViewSuite` imports `wiggly.gin.gen.GameGen` for this test. Each lobby row has a button whose label is `Join <host>`, so that every row names what it joins.
The join-by-id button reads `Join` with no trailing space, so the count above holds.

- [ ] **Step 3: Run the tests to make sure that they fail**

Run: `sbt ui/test`
Expected: compile failure, because `Model.Start` takes three arguments and `Waiting` does not
exist.

- [ ] **Step 4: Write the age**

```scala
package wiggly.gin.ui

import wiggly.gin.core.domain.ListedAt

/** How long a game in the lobby has waited, in the words a row shows. */
object Waiting {

  def age(since: ListedAt, now: Long): String = {
    val minutes = (now - since.millis).max(0L) / 60000L

    if (minutes == 0L) "waiting under a minute" else s"waiting $minutes min"
  }
}
```

- [ ] **Step 5: Change the model, the messages and the update**

`Model.Start` becomes:

```scala
  /** `now` is the browser's clock in milliseconds, held in the model so that `View` stays a pure
    * function of it and a row's waiting time can be tested.
    */
  case Start(
      name: String,
      joining: String,
      lobby: List[Listing],
      now: Long,
      error: Option[String]
  )
```

`Msg` gains:

```scala
  case Listed(listings: List[Listing])
  case Tick(now: Long)
  case JoinListed(id: GameId)
```

`Update` gains three cases beside the other `Model.Start` cases:

```scala
      case (start: Model.Start, Msg.Listed(listings)) =>
        (start.copy(lobby = listings), Cmd.None)

      case (start: Model.Start, Msg.Tick(now)) =>
        (start.copy(now = now), Cmd.None)

      case (start: Model.Start, Msg.JoinListed(id)) =>
        named(start)(Api.join(id.value, _))
```

The cases from Task 3 keep using `start.copy`, so they need no change.

- [ ] **Step 6: Subscribe to the lobby**

In `Events.scala`, extract the `EventSource` code into one private function and use it twice:

```scala
object Events {

  def watch(seat: Credentials): Sub[IO, Msg] =
    stream[PlayerView](
      s"events-${seat.id.value}",
      s"/api/v1/games/${seat.id.value}/events?token=${seat.token.value}"
    )(Msg.Saw.apply)

  val lobby: Sub[IO, Msg] = stream[List[Listing]]("lobby", "/api/v1/lobby/events")(Msg.Listed.apply)

  private def stream[A: Decoder](key: String, url: String)(seen: A => Msg): Sub[IO, Msg] = {
    Sub.make[IO, dom.MessageEvent, Msg, dom.EventSource](key)(
      (emit: Either[Throwable, dom.MessageEvent] => Unit) =>
        IO {
          val source = new dom.EventSource(url)
          source.onmessage = (event: dom.MessageEvent) => emit(Right(event))
          // A browser reconnects a dropped EventSource on its own. Reporting the drop would show
          // the player a failure that has already fixed itself.
          source.onerror = (_: dom.Event) => ()
          source
        }
    )((source: dom.EventSource) => IO(source.close()))((event: dom.MessageEvent) =>
      Some(parser.decode[A](event.data.toString).fold(error => Msg.Failed(error.getMessage), seen))
    )
  }
}
```

Import `io.circe.Decoder` and `wiggly.gin.core.domain.Listing`. Keep the object's scaladoc, and
add a sentence that the lobby stream needs no token.

In `Main.scala`, `init` builds
`Model.Start(Profile.recall.getOrElse(""), arriving.getOrElse(""), Nil, js.Date.now().toLong, None)`.
The start case of `subscriptions` becomes:

```scala
      case Model.Start(_, _, _, _, _) =>
        Events.lobby |+| Sub.every[IO](30.seconds, "lobby-clock").map(date => Msg.Tick(date.getTime().toLong))
```

Import `scala.scalajs.js`, `scala.concurrent.duration.*` and `cats.syntax.all.*`. If `|+|` does
not resolve for `Sub`, use `Sub.Batch(Events.lobby, …)` instead.

- [ ] **Step 7: Draw the lobby**

In `View.scala`, `start` draws the lobby between the create button and the join-by-id line:

```scala
      button(onClick(Msg.Create))(text("Create a game")),
      lobby(model.lobby, model.now),
      p(text("or join one you were sent:")),
```

Add:

```scala
  private def lobby(listings: List[Listing], now: Long): Html[Msg] = {
    val rows = listings.map { listing =>
      li(cls := "listing")(
        span(cls := "host")(text(listing.host.value)),
        span(cls := "age")(text(Waiting.age(listing.since, now))),
        button(onClick(Msg.JoinListed(listing.id)))(text(s"Join ${listing.host.value}"))
      )
    }

    div(cls := "lobby")(
      h2(text("Games waiting for a player")),
      if (rows.isEmpty) p(text("Nobody is waiting. Create a game and it shows here."))
      else ul(rows)
    )
  }
```

In `awaiting`, the text under the name becomes `p(text("Your game is in the lobby. Or send this link:"))`
in place of `p(text("Send them this link:"))`.

In `gin.css`, add a rule for `.lobby .listing`. Put the host, the age and the button on one line.
Give the age a muted color. Use the spacing that `.start` already uses.

- [ ] **Step 8: Run the tests to make sure that they pass**

Run: `sbt ui/test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add modules/ui modules/server/src/main/resources/web/gin.css
git commit -m "Show the lobby on the start screen"
```

---

### Task 9: Write it down and run the gate

**Files:**
- Modify: `docs/DESIGN.md`
- Modify: `docs/SPEC-LOBBY.md`
- Modify: `docs/SPEC-WEB-UI.md`
- Modify: `docs/PLAN-LOBBY.md`

**Interfaces:**
- Consumes: everything above.
- Produces: documentation that matches the code.

- [ ] **Step 1: Update the design document**

In `docs/DESIGN.md`, the ports section says there are seven ports. Add two rows to the table:

```markdown
| `LobbyService` | The games waiting for an opponent, as a stream. | `Lobbies` in `core/service` |
| `LobbyBoard` | Where the lobby is kept. | `MemoryLobbyBoard` |
```

After the paragraph about `Games`, state the presence rule in two or three sentences. Link to
`SPEC-LOBBY.md` for the detail. In the opening section, change the account
of how the second player arrives: they pick the game from the lobby, or they open a link. Name the
new `400 malformed-name` refusal wherever the document lists the refusals.

In `docs/SPEC-WEB-UI.md`, the start screen row of the first table gains "and, since the lobby, a
name box and the list of waiting games".

- [ ] **Step 2: Record where the code came out different**

Add a section "Where the code came out different" to `docs/SPEC-LOBBY.md`, in the same shape as
the one in `SPEC-WEB-UI.md`. Fill it with each place the code differs from the spec. If nothing
differs, write one sentence that says so. Set a status line under the header of this plan, as
`PLAN-WEB-UI.md` has.

- [ ] **Step 3: Parse the Mermaid blocks**

Run this for each changed document that holds a Mermaid block:

```bash
mkdir -p /tmp/mermaid-check && cd /tmp/mermaid-check && npm install mermaid jsdom
```

Then set the `jsdom` globals and call `await mermaid.parse(block)` for each block.
Expected: no parse error.

- [ ] **Step 4: Run the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`
Expected: PASS. If `scalafmtCheckAll` fails, run `sbt scalafmtAll` and commit the result on its own.

Run: `sbt coverageAll`
Expected: PASS, above the floors in `build.sbt`.

- [ ] **Step 5: Play it through by hand**

Run `sbt server/run`. Open two browsers, or one normal window and one private window, at the
server. In the first, type a name and create a game. In the second, make sure that the game shows
in the lobby with the host's name, then type a name and click its Join button. Make sure that the
row leaves the lobby, and that both tables show both names. Close the first window before a join
in a second game, and make sure that its row leaves the lobby.

This step needs a person at a browser. If you cannot do it, say so in the status line.

- [ ] **Step 6: Commit**

```bash
git add docs
git commit -m "Write down the lobby"
```
