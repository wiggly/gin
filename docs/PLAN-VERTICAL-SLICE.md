# Plan: ports and the first vertical slice

A working plan for step 5 of the [roadmap](ROADMAP.md). The rules of the game are in
[GAME-FLOW.md](GAME-FLOW.md) and this plan does not restate one, so a disagreement between the two
documents is a bug in this one. Where a file goes is settled by the table in the
[README](../README.md), which this step fills in rather than changes.

This is the first step with effects in it. The domain stays pure, the ports are traits in `F[_]`,
and every piece of technology is named in exactly one adapter.

## Decisions settled up front

| Decision | Choice | Why |
| --- | --- | --- |
| What a client creates | A match, which deals its own rounds | `Match` otherwise has no caller. Dealing the next round when one ends is the job step 4 left for whoever holds a game, and this is that holder. |
| Transport | REST for the moves, server-sent events for the state | A move is a POST with a body and a reply, so duplex framing buys nothing. The only push a client needs is "the state changed, here is your view". Server-sent events are an ordinary GET, pass through proxies, and browsers reconnect on their own. |
| The broker | Behind a `GameEvents` port, an fs2 `Topic` per game in the adapter | A WebSocket later is then an adapter beside the stream rather than a change to `core`. |
| Identity | An opaque token per seat, stored with the game | No secret to configure and no key to rotate, and a leaked token is withdrawn by forgetting it. A request carries it as a bearer token, and the stream also takes it as a query parameter, because `EventSource` cannot set a header. |
| Joining | Create, then join | Creating leaves the game waiting and returns the host's token. A join mints the second token and deals the first round. It costs a state before the first deal, which is a state the view has to describe anyway once a game can be created from a lobby. |
| The seats | The host is always `Player.One`, and the player who joins deals the first round | Removes a field from the waiting state and a random choice from the join. The one who waited gets the first offer of the upcard in exchange, and the deal alternates from then on. |
| Between rounds | Deal at once, and keep the round that ended in the game | Nothing for two clients to coordinate, no extra state to guard on every route, and none of the working lost. The view carries the previous round until the next one replaces it. |
| `RoundResult` | New, and `RoundScore.of` delegates to it | A view of a finished round needs the count and not just the number. It also tightens step 4: gin is a defender with no melds to lay off onto, so `Defence.against(hand, Nil)` is the gin rule, and "gin blocks layoffs" becomes one expression instead of two branches that can come to disagree. |
| `GameRepository.update` | Takes a pure function | That is what a `Ref` can apply atomically. An effectful modify needs a lock held across an effect, or a second write that other requests can see between. |
| The deck for the next deal | Shuffled before the update, whether or not it is needed | The consequence of the line above. A wasted shuffle of 52 cards on a move that does not end a round is the cheapest of the three options. |
| `PlayerView` | An enum of three cases, never a `GameState` | The in-play case has no field that could hold the other hand or the order of the stock, so a leak is a new field rather than a forgotten guard. |
| The codecs | Written by hand, in the http adapter | The wire format is chosen rather than derived from Scala names, and `server` needs no new dependency. |
| The redaction test | Over the encoded JSON, in `server` | A field added later appears in the payload whether or not anybody remembered a traversal. |

Two dependencies change. `core` gains `fs2-core`, because the event port returns a `Stream`.
`server` gains nothing: http4s already brings fs2, and the codecs are hand-written so `circe-core`
is enough.

## 1. The types the application needs

```
modules/core/src/main/scala/wiggly/gin/core/domain/Game.scala
modules/core/src/main/scala/wiggly/gin/core/domain/RoundResult.scala
modules/core/src/main/scala/wiggly/gin/core/domain/PlayerView.scala
modules/core/src/main/scala/wiggly/gin/core/domain/GameFault.scala
modules/core/src/main/scala/wiggly/gin/core/domain/RoundScore.scala   (delegates to RoundResult)
modules/core/src/test/scala/wiggly/gin/core/domain/GameSuite.scala
modules/core/src/test/scala/wiggly/gin/core/domain/RoundResultSuite.scala
modules/core/src/test/scala/wiggly/gin/core/domain/PlayerViewSuite.scala
modules/core/src/test/scala/wiggly/gin/gen/GameGen.scala              (gains games and views)
```

All of it is pure, and all of it stays in `core/domain` because none of it names a technology. A
token is a string that a player shows; how one is minted is the adapter's business.

```scala
final case class GameId(value: String)
final case class Token(value: String)

final case class Tokens(one: Token, two: Token) {
  def apply(player: Player): Token
  def holder(token: Token): Option[Player]
}

sealed trait Game {
  def id: GameId
  def holder(token: Token): Option[Player]
}

object Game {
  case class AwaitingOpponent(id: GameId, host: Token)
  case InPlay(
      id: GameId,
      tokens: Tokens,
      ledger: Match.InProgress,
      round: GameState.InProgress,
      previous: Option[RoundResult]
  )
  case class Over(id: GameId, tokens: Tokens, ledger: Match.Finished, previous: RoundResult)
}
```

A sealed trait rather than the enum this plan first drew, and for the reason `GameState` is one: a
case that carries `id` cannot sit under an enum that also declares `def id`, because the case's
field would be overriding it. Implementing an abstract member has no such problem.

The invariant worth naming: a stored game never holds a finished round. The moment a round ends it
is scored and the next is dealt, or the match is over and there is no round to hold. That is why
`InPlay` names `GameState.InProgress` rather than `GameState`, and it is what makes every route
past the guard total.

Two functions move a game on, and both take a deck, because both can deal one:

```scala
def joined(guest: Token, deck: Deck): Either[GameFault, Game]
def played(player: Player, move: Move, next: Deck): Either[GameFault, Game]
```

`joined` turns the waiting state into a first round dealt with the guest as dealer. `played` applies
a move, and when the move ends the round it scores it, folds the score into the ledger, and either
deals `next` or leaves the match over. The deck it does not use is discarded, which is the price of
an update that a `Ref` can apply in one go.

`RoundResult` is what the end of a round makes public, which is the same thing as the working
behind its score. A round nobody knocked reveals nothing, and a knock puts both hands on the table.

```scala
final case class RoundResult(
    outcome: Outcome,
    knocker: Option[Arrangement],
    defender: Option[Defence],
    score: RoundScore
)

object RoundResult {
  def of(round: GameState.Finished): RoundResult
}
```

`RoundScore.of` moved here rather than delegating, because once `RoundResult.of` existed the only
caller left for it was a test. `RoundScoreSuite` became `RoundResultSuite` and every number it
pinned stayed the same, which is the check that the move was a refactor. `RoundScore` keeps the two
bonuses, because they belong to the score.

`PlayerView` is the redacted state, and the redaction is in the shape rather than in a filter:

```scala
enum PlayerView {
  case AwaitingOpponent(id: GameId, you: Player)

  case InPlay(
      id: GameId,
      you: Player,
      arrangement: Arrangement,
      dealer: Player,
      onTurn: Player,
      phase: Phase,
      upcard: Option[Card],
      stockSize: Int,
      discardSize: Int,
      opponentSize: Int,
      totals: Tally,
      previous: Option[RoundResult]
  )

  case Over(id: GameId, you: Player, result: MatchResult, previous: RoundResult)
}

object PlayerView {
  def of(game: Game, you: Player): PlayerView
}
```

`arrangement` is the requester's own cards with the best arrangement the rules make of them, which
is what tells them whether they can knock. The opponent is a count and the stock is a count.
`phase` goes over unredacted, which is safe on inspection: the only card it names is the one its
subject took from the pile, in the open. `onTurn` is carried rather than left to the client,
because `AwaitingOpeningDraw` names nobody and resolving it needs the seating. The discard pile is
a count and an upcard, because a pile is squared at a real table and only its top card is in play.

**Tests.** `holder` finds the player a token belongs to and nobody for a token neither player
holds. `RoundResult.of` reveals both hands for a knock and neither for a dead round, and its score
agrees with `RoundScore.of` for every finished round a walk can reach. `PlayerView.of` shows a
player their own cards and the right counts, and shows the same game differently to each player.

The redaction property here is the one a pure test can make: the cards in a view of a round in
play are exactly the requester's hand plus the upcard. The stronger version, over everything that
actually goes over the wire, is stage 4.

## 2. The ports and the service

```
modules/core/src/main/scala/wiggly/gin/core/port/GameRepository.scala
modules/core/src/main/scala/wiggly/gin/core/port/GameEvents.scala
modules/core/src/main/scala/wiggly/gin/core/port/Shuffler.scala
modules/core/src/main/scala/wiggly/gin/core/port/Secrets.scala
modules/core/src/main/scala/wiggly/gin/core/port/GameService.scala
modules/core/src/main/scala/wiggly/gin/core/service/Games.scala
modules/core/src/test/scala/wiggly/gin/fake/FakeRepository.scala
modules/core/src/test/scala/wiggly/gin/fake/FakeEvents.scala
modules/core/src/test/scala/wiggly/gin/fake/FixedShuffler.scala
modules/core/src/test/scala/wiggly/gin/fake/CountingSecrets.scala
modules/core/src/test/scala/wiggly/gin/core/service/GamesSuite.scala
```

Four outbound ports and one inbound one. `GameService` is the port the HTTP layer drives and
`Games` is the code that satisfies it.

```scala
trait GameRepository[F[_]] {
  def create(game: Game): F[Unit]
  def read(id: GameId): F[Option[Game]]
  def update(id: GameId)(change: Game => Either[GameFault, Game]): F[Either[GameFault, Game]]
}

trait GameEvents[F[_]] {
  def publish(game: Game): F[Unit]
  def watch(id: GameId): Stream[F, Game]
}

// A signal per game rather than the topic this plan first named. An event is a whole game, so a
// watcher that falls behind wants the state as it now is and not the states it missed. A signal
// gives that with no buffer to grow and no publisher to hold up, and because it holds the current
// value, `discrete` opens with it: there is no window between asking and listening to close, and
// no subscribe-then-read dance to get right.

trait Shuffler[F[_]] { def shuffled: F[Deck] }

trait Secrets[F[_]] {
  def gameId: F[GameId]
  def token: F[Token]
}

trait GameService[F[_]] {
  def create: F[Credentials]
  def join(id: GameId): F[Either[GameFault, Credentials]]
  def look(id: GameId, token: Token): F[Either[GameFault, PlayerView]]
  def play(id: GameId, token: Token, move: Move): F[Either[GameFault, PlayerView]]
  def watch(id: GameId, token: Token): F[Either[GameFault, Stream[F, PlayerView]]]
}

final case class Credentials(id: GameId, you: Player, token: Token)
```

`Games` resolves the token to a player, calls `Game.played`, writes the result through `update` and
publishes it. What it returns is the mover's own view of what they did, so a client needs no second
request to see the result of its move. Creating and joining publish too, because a host waiting on
the stream wants to know that the game started.

A watcher is handed the game as it stands and then every state after it, which the signal gives
for nothing: nothing has to be ordered against a separate read.

**Tests.** The fakes are `Ref`-backed and live in `wiggly.gin.fake` beside the generators, which
the server module already shares through the `test->test` dependency. `FixedShuffler` hands out a
deck the test wrote, so a game played through the service is as predictable as one played through
`GameState`.

Worked examples: a created game is waiting and holds the host's token; a join deals the first round
and offers the upcard to the host; a second join is refused; a move by the player off turn is
refused with the rule's own error inside `Illegal`; a token neither player holds is refused whether
it is looking or playing; a round that ends is scored, folded into the ledger and followed by a
fresh deal, with the previous round still in the view; a match that ends leaves a game that refuses
every move. A property: every legal move through the service leaves a game whose cards are still
the deck it was dealt from.

## 3. The outbound adapters

```
modules/server/src/main/scala/wiggly/gin/server/adapter/memory/MemoryGameRepository.scala
modules/server/src/main/scala/wiggly/gin/server/adapter/memory/MemoryGameEvents.scala
modules/server/src/main/scala/wiggly/gin/server/adapter/random/RandomShuffler.scala
modules/server/src/main/scala/wiggly/gin/server/adapter/random/RandomSecrets.scala
modules/server/src/test/scala/wiggly/gin/server/adapter/memory/MemoryGameRepositorySuite.scala
modules/server/src/test/scala/wiggly/gin/server/adapter/memory/MemoryGameEventsSuite.scala
modules/server/src/test/scala/wiggly/gin/server/adapter/random/RandomSecretsSuite.scala
```

Each is a `Resource` or an `F` that builds one, so `Main` acquires them in order and nothing
constructs a `Ref` at the top level.

The store is a `Ref[F, Map[GameId, Game]]` and `update` is one `modify`, which is what makes two
moves arriving together safe. The broker is a `Ref[F, Map[GameId, Topic[F, Game]]]` that makes a
topic the first time a game is published or watched. `RandomSecrets` draws from
`Random.javaSecuritySecureRandom`, because a token that can be guessed is not a token.
`RandomShuffler` draws from an ordinary `Random[F]`, because a shuffle needs to be unrepeatable
rather than unguessable, and it rebuilds a `Deck` through `Deck.from` so a bad shuffle fails loudly.

**Tests.** The store keeps what it was given, refuses an id it does not hold, and applies
concurrent updates one at a time. The test for the last one is a race for the second seat: a
hundred joins fired at once against one waiting game leave exactly one that succeeded and
ninety-nine refused with `AlreadyFull`. A store that read and then wrote would seat several. The broker delivers a published game to every watcher and nothing to a
watcher of another game. Secrets never repeats a token in ten thousand draws, and a shuffled deck
is always a deck.

## 4. The HTTP adapter

```
modules/server/src/main/scala/wiggly/gin/server/adapter/http/GameRoutes.scala
modules/server/src/main/scala/wiggly/gin/server/adapter/http/Codecs.scala
modules/server/src/main/scala/wiggly/gin/server/config/AppConfig.scala   (gains the heartbeat)
modules/server/src/main/resources/application.conf
modules/server/src/main/scala/wiggly/gin/server/Main.scala               (wires it all)
modules/server/src/test/scala/wiggly/gin/server/adapter/http/GameRoutesSuite.scala
modules/server/src/test/scala/wiggly/gin/server/adapter/http/RedactionSuite.scala
modules/server/src/test/scala/wiggly/gin/server/adapter/http/CodecsSuite.scala
```

```
POST /api/v1/games              201  {"id":…,"you":"one","token":…}
POST /api/v1/games/{id}/join    200  {"id":…,"you":"two","token":…}
GET  /api/v1/games/{id}         200  the view      Authorization: Bearer <token>
POST /api/v1/games/{id}/moves   200  the view      Authorization: Bearer <token>
GET  /api/v1/games/{id}/events  200  text/event-stream of views, bearer or ?token=
```

A move is a tagged object: `{"move":"draw-stock"}`, or `{"move":"knock","card":{"rank":"ace",
"suit":"spades"}}`. A fault becomes a status with the existing `{"error":…}` body, which every
other response in this server already uses.

| Fault | Status |
| --- | --- |
| `NoSuchGame` | 404 |
| `NotAPlayer` | 403 |
| `AlreadyFull` | 409 |
| `NotInPlay` | 409 |
| `Illegal(error)` | 422, with the rule's own name in the body |
| No token at all | 401, which the fault table has no case for because the service is never asked |
| A body that is not a move | 400 |

The stream carries a comment heartbeat on an interval, because an idle connection through a proxy
is dropped. That is the one new configuration value, `GIN_HTTP_EVENT_HEARTBEAT`, defaulting to 15
seconds.

**Tests.** `GameRoutesSuite` drives the real service over the fakes from stage 2, so a test that
asserts a status has played a real game to get there. Every row of the fault table as a worked
example. `CodecsSuite` round-trips a move and a view.

`RedactionSuite` is the one the roadmap asks for. It plays a game, encodes each player's view, and
collects every card-shaped object anywhere in the JSON. Those cards must be a subset of what that
player is entitled to see, which is their own hand, the upcard, and whatever the end of a round
made public. It scans the payload rather than the Scala value so that a field added later is
covered whether or not anybody remembers this suite.

One thing that plan did not foresee. The claims about the stock and the other hand are made against
the payload with `previous` set aside, because a knock puts both hands on the table and those cards
have since been shuffled back into a fresh deal, so they turn up in the stock of the round in play.
They say nothing about where anything is now, and the version of the claim that keeps `previous` in
is simply false. The first test, which allows nothing but the player's own hand and the upcard,
runs on games that have no previous round.

## Where the code came out different

Everything above is what landed, with these exceptions, each noted where it belongs:

| The plan said | The code does | Why |
| --- | --- | --- |
| `enum Game` | A sealed trait | An enum case cannot carry `id` beside an enum-level `def id`. |
| `RoundScore.of` delegates | It moved to `RoundResult.of` | Delegating would have left it with no caller but a test. |
| A topic per game, subscribed before the current state is read | A signal per game | A signal holds the current value, so the ordering problem does not arise, and a slow watcher costs nothing. |
| Five faults and five statuses | Seven answers | A request with no token is 401 and a body that is not a move is 400, neither of which reaches the service. |
| The redaction check covers the whole payload | It sets `previous` aside for two of its three claims | The previous round is public and its cards have been redealt, so they appear in the stock of the round in play. |

## 5. The documents

`README.md` gains the two new adapter directories in its tree and its table, the four routes and
the stream in its endpoint table, the new environment variable, and a line about where the fakes
live. `ROADMAP.md` marks step 5 done and closes both open decisions with what was chosen. This plan
gains a note wherever the code came out different from it.

## Order of work

Test first throughout, in the order above. Five commits, each green on its own:

1. The application's types, and `RoundScore` delegating to `RoundResult`.
2. The ports, the service and the fakes.
3. The outbound adapters.
4. The HTTP adapter and the wiring in `Main`.
5. The documents.

Before handing the work over, run the gate from `CLAUDE.md`:

```bash
SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test
```

The build also gates coverage at 85% of statements and 50% of branches across both modules, so run
`sbt coverageAll` once the last commit lands. `Main` and `HttpServer` are already excluded from the
figure, and the new adapter code is not, so the store, the broker and the routes all need tests
rather than a wider exclusion.

## Deliberately left out

A token lives as long as the process, because the store does. So does a game. The persistent
repository is already on the roadmap's later list and it is the same port, so nothing here has to
change to get one.

A topic is never removed once made, so a long-lived process accumulates one per game played. It is
a `Topic` and a map entry per game rather than a leak that grows on its own, and the fix belongs
with the persistent store, where a game's end has somewhere to be recorded.

Nothing stops the host joining their own game and playing both sides. Nothing stops a third party
who learns a game id from joining it either, which is the same question: a game id is not a
capability, and the token is. Both want the identity the roadmap still leaves open, and neither
makes the redaction weaker than it says it is.

A WebSocket is not here. It is an adapter over `GameEvents` beside the stream, which is why the
broker went behind a port rather than into the routes.

There is no lobby: no listing of games, no matchmaking, and no way to find a game you were not told
the id of. There is also no CORS, no rate limit and no request id, all of which are middleware in
`GinApi` when somebody needs them.
