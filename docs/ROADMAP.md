# Roadmap

Where the work went, and why in this order. Steps 1 to 4 are pure code in `core` with no `IO`;
step 5 is where the `server` adapter finally had something to expose.

Status: steps 1 to 5 are done. `core` holds the card model, the melds and the deadwood search, the
round as a state machine, the scoring that adds rounds up into a match, and the ports and service
that drive all of it. `server` serves a whole game over HTTP, with a stream a player can watch.
Four working plans record how, one per step: [cards and melds](PLAN-CARDS-AND-MELDS.md), [the state
machine](PLAN-STATE-MACHINE.md), [scoring](PLAN-SCORING.md) and [the vertical
slice](PLAN-VERTICAL-SLICE.md).

The rules of the round and of the match are written down once, in [the game flow
document](GAME-FLOW.md). It is the permanent reference, and the plans point at it rather than
restating it.

## Settled

Testing is [weaver](https://typelevel.org/weaver-test/) 0.13 with `weaver-scalacheck`, property-based
by default; suites are objects named `*Suite`. Formatting is scalafmt, pinned against the Scala 3
brace-removal rewrites. Conventions are in the README; this is not worth re-opening at step 1.

Coverage is sbt-scoverage, run on demand rather than as part of the gate, with a floor that fails
`coverageAggregate` if the combined figure drops. `sbt coverageAll` runs it; see the README.

Still absent by choice: CI. The build gates on `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll test`
whenever someone decides to wire it up.

## 1. Cards (`core`) — done

`Rank`, `Suit`, `Card`, the deadwood value of a card (A=1, face=10, otherwise pip), and the ordered
52-card deck. Everything below depends on this, and there is nothing to decide.

Tests: 52 distinct cards; the value mapping at the A/10/face boundaries.

## 2. Melds and deadwood (`core`) — done

`Meld` as either a set (3–4 of a rank) or a run (3+ in suit sequence), meld validation, and
`bestArrangement(hand)` returning the arrangement that minimises deadwood.

This is the only genuinely hard algorithm in the game: a card may belong to two candidate melds and
the arrangement has to choose the better split. Start with brute force over candidate melds — a hand
is 10–11 cards, so the search space is small, and a correct slow version beats a clever wrong one.
Optimise only if a profile says to.

Tests: hands with hand-computed deadwood; the ambiguous-card case; gin (0 deadwood); the knock
boundary (≤ 10).

## 3. The state machine (`core`) — done

In `core/domain`, alongside the cards: `GameState` (stock, discard pile, both hands, whose turn,
phase of turn) and `Move` — draw from stock, draw from discard, pass, discard, knock — behind a
single pure total function:

```
(state, player, move) => Either[GameError, GameState]
```

[The game flow document](GAME-FLOW.md) holds the states, the transitions, the guards and the error
each guard returns. [A working plan](PLAN-STATE-MACHINE.md) records the files and the order they
were written in.

Two moves that an earlier draft of this list named are gone. Gin is a knock worth nothing rather
than a move of its own. A layoff is computed with the score in step 4 rather than played out,
because a layoff only ever cuts deadwood and so no player would decline one.

Shuffling enters as a port rather than a call to `Random`: `deal` takes an already-shuffled deck, so
tests deal a known deck and the effect stays at the edge of the application.

Tests: turn order; draw-before-discard; illegal moves rejected without mutating state; stock
exhaustion; knock above and below the threshold.

## 4. Scoring (`core`) — done

`RoundScore.of` turns a finished round into points. `Defence` works out what the other player is
left holding once every layoff is taken, in one search rather than a best arrangement followed by a
layoff pass, because the two compete over the card that joins a hand to the end of a knocker's run.
`Match` adds the rounds up to a target of 100 and applies the three bonuses.

The numbers and the rules are in [the game flow document](GAME-FLOW.md), which also records the
three answers this step owed the match level. [A working plan](PLAN-SCORING.md) records the files
and the order they were written in.

A match is a ledger rather than a machine that owns the round, so step 5 takes on the job of
dealing each round with the seating the ledger gives and playing the score back in when the round
ends.

Tests: each outcome and each bonus worked by hand, with properties for the arithmetic and for the
three lists a defence splits a hand into.

## 5. Ports and the first vertical slice (`core` + `server`) — done

A client creates a game, a second player joins it, and the two play a whole match of rounds to a
hundred. Five ports: `GameService` is the inbound one the HTTP layer drives, and `GameRepository`,
`GameEvents`, `Shuffler` and `Secrets` are the outbound ones the adapters satisfy. `Games` in
`core/service` holds the ledger and the round in play together, which is the job step 4 left for
whoever holds a game. The store and the broker are in `server/adapter/memory` and the shuffle and
the tokens in `server/adapter/random`. The README has the layout and the routes; [a working
plan](PLAN-VERTICAL-SLICE.md) records the files and the order they were written in.

The design point that mattered: **a player's view is redacted.** `PlayerView` is a distinct type
and never a `GameState`, the other player is a count and the stock is a count, and neither `Game`
nor `GameState` has an encoder at all, so the other shapes cannot reach a client by accident. The
check runs over the encoded JSON rather than the Scala value, so a field added later is covered
whether or not anybody remembers the suite.

## Decisions that were open

Both were settled at the start of step 5, before the routes hardened.

**Transport.** REST for the moves and a server-sent event stream for the state. A move is a POST
with a body and a reply, so duplex framing bought nothing, and the only push a client needs is
"the state changed, here is your view". The broker sits behind `GameEvents`, so a WebSocket is an
adapter beside the stream rather than a change to `core`.

**Identity.** An opaque token per seat, minted from a secure source and stored with the game. No
secret to configure and no key to rotate, and a leaked token is withdrawn by forgetting it.

What is still open is identity in the larger sense: a token says which seat is asking and nothing
about who holds it, so there are no accounts, nothing stops the host joining their own game, and
anybody who learns a game id can take the second seat. None of that makes the redaction weaker than
it claims to be.

## Later

- Persistent `GameRepository` adapter, once the in-memory one is outgrown. A game and a token both
  last only as long as the process does.
- A WebSocket adapter over `GameEvents`, if a client ever wants one connection for everything.
- A lobby: listing games, matchmaking, and some way to find a game nobody told you the id of.
- Middleware in `GinApi` for CORS, rate limiting and a request id, when somebody needs them.
- Container packaging (sbt-native-packager), per the 12-factor goal in `CLAUDE.md`.