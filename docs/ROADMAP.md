# Roadmap

Where the work goes next, and why in this order. Steps 1–4 are pure code in `core` with no `IO`;
step 5 is where the `server` adapter finally has something to expose.

Status: the `server` module exists and serves `/health`, with `/api/v1` reserved and currently
backed by `HttpRoutes.empty`. `core` holds the card model, the melds and the deadwood search, the
round as a state machine, and the scoring that adds rounds up into a match: steps 1 to 4 are done.
Three working plans record how, one per step: [cards and melds](PLAN-CARDS-AND-MELDS.md), [the
state machine](PLAN-STATE-MACHINE.md) and [scoring](PLAN-SCORING.md).

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

## 5. Ports and the first vertical slice (`core` + `server`)

Ports: `GameRepository` (in-memory `Ref` adapter to begin with) and a `GameService` the HTTP layer
drives. `GameService` is also where the match ledger and the round in play are held together, which
is the one thing step 4 left for somebody else to do. Both traits go in `core/port`, the code that
drives the domain behind `GameService` goes in `core/service`, and the `Ref` store goes in
`server/adapter/memory` next to the http adapter. The README explains the layout.

The design point that matters here: **a player's view must be redacted.** The opponent's hand and
the order of the stock are not the requester's to see. Make that a distinct type — `PlayerView`,
never `GameState` — so it cannot leak by accident, and test that it cannot.

Then the routes that have been waiting:

- `POST /api/v1/games` — create
- `POST /api/v1/games/{id}/moves` — play
- `GET  /api/v1/games/{id}` — the redacted view

wired into `Main` in place of `HttpRoutes.empty`.

## Open decisions

Both are additive to steps 1–4, so neither blocks the domain work, but both want settling before the
routes in step 5 harden.

**Transport.** REST alone means a player polls to discover that the opponent has moved. A WebSocket
per player fed by an fs2 `Topic` is the natural fit for a multiplayer game and Ember supports it
directly — but it changes the shape of the API.

**Identity.** "Player 2 joins game X" needs some notion of who is asking. A signed opaque token is
enough to start with. Redaction in step 5 is meaningless without it.

## Later

- Persistent `GameRepository` adapter, once the in-memory one is outgrown.
- Container packaging (sbt-native-packager), per the 12-factor goal in `CLAUDE.md`.