# Plan: the game state machine

A working plan for step 3 of the [roadmap](ROADMAP.md). The rules it implements are in
[GAME-FLOW.md](GAME-FLOW.md), which is the permanent reference. This plan does not restate a rule,
so a disagreement between the two documents is a bug in this one.

All of it is pure code in `core/domain`. There is no `F[_]` and no library but `cats-core`, which
is what the domain already depends on.

## Decisions settled up front

These are cheap now and expensive once the state machine has landed on top of them.

| Decision | Choice | Why |
| --- | --- | --- |
| The state type | `GameState` splits into `InProgress(table, phase)` and `Finished(table, outcome)` | The split falls where the machine splits. A finished round has no player on turn, and the type says so, which turns a guard repeated in every branch into one `Left(RoundOver)`. |
| `Phase` | `UpcardOffered`, `AwaitingOpeningDraw`, `AwaitingDraw`, `AwaitingDiscard` | The four live states. `AwaitingDiscard` is the only one carrying the card taken from the pile, so the field exists exactly where the rule needs it. |
| `Player` | `Dealer` and `NonDealer` | The only two identities a single round has. It removes any need for the table to record who dealt. Binding a person to a seat belongs to the identity decision the roadmap leaves open for step 5. |
| Hands | `Hands(dealer, nonDealer)` with a total `apply(player)` | Two players and two named fields, so a hand lookup cannot fail and no `Option` spreads through the transitions. A `Map` would. |
| `Hand` | A thin wrapper whose `remove` returns `Option` and takes one card out, not every copy | The step 2 plan deferred it to here. It puts "you do not hold that card" in one place instead of in every discard path. `filterNot` and a single removal are indistinguishable until a duplicate exists, and then the first one destroys a card. |
| `Deck` | A private constructor and `Deck.from(cards): Option[Deck]`, where `Some` means a permutation of the 52 | `deal` is the only door a malformed set of cards can enter through, because no transition ever creates a card. One comparison against the sorted deck rejects a duplicate, a wrong count and a foreign card together, so `deal` stays total and step 5's shuffle port gains a type to produce. |
| A duplicate-free `Hand` | Not a `SortedSet`, deliberately | The invariant that matters spans the whole table, not one hand: a card in two piles is the real failure and a set cannot see it. A set also turns `add` of a held card into a silent no-op, trading a visible duplicate for an invisible loss. The `Deck` boundary and the partition property below carry the guarantee instead. |
| Knock and gin | One `Knock` move | Gin is a knock worth nothing. One move and one guard cannot disagree about a hand of zero deadwood the way two would. |
| Layoffs | Computed in step 4, not played | A layoff only ever cuts deadwood, so no player would decline one. The round needs no layoff phase and no `LayOff` move. |
| Shuffling | No port in step 3 | `deal` takes a deck that is already shuffled, so step 3 needs nothing in `core/port`. The port arrives with step 5. |
| `legalMoves` | In `GameGen`, not in the domain | Two tests need it and no production caller does. Step 5 probably wants it, when a client asks what it can do. |

## 1. Deck, Player and Hand

```
modules/core/src/main/scala/wiggly/gin/core/domain/Deck.scala        (exists, gains the type)
modules/core/src/main/scala/wiggly/gin/core/domain/Arrangement.scala (exists, one doc comment)
modules/core/src/main/scala/wiggly/gin/core/domain/Player.scala
modules/core/src/main/scala/wiggly/gin/core/domain/Hand.scala
modules/core/src/test/scala/wiggly/gin/core/domain/DeckSuite.scala   (exists, gains cases)
modules/core/src/test/scala/wiggly/gin/core/domain/PlayerSuite.scala
modules/core/src/test/scala/wiggly/gin/core/domain/HandSuite.scala
```

`Deck` stops being a bare list of 52 cards and becomes a type that cannot hold anything else:

```scala
final case class Deck private (cards: List[Card])

object Deck {
  val ordered: Deck
  def from(cards: List[Card]): Option[Deck]   // Some iff sorting gives `ordered` back
}
```

`Deck.ordered` is already the 52 cards and keeps its name. The three generator call sites that
read it as a list become `Deck.ordered.cards`, which is the whole cost of the change.

`Arrangement.best` currently documents the assumption it cannot enforce, saying that a repeated
card is not rejected and simply ends up as deadwood. That was right at step 2, when nothing but a
test built a hand. With `Deck` guarding `deal` the assumption becomes true rather than expected,
and the doc comment moves to saying so.

`Player` is an enum of two cases with `other`. `Hand` wraps the cards a player holds:

```scala
final case class Hand private (cards: List[Card]) {
  def size: Int
  def add(card: Card): Hand
  def remove(card: Card): Option[Hand]
  def arrangement: Arrangement
  def deadwoodValue: Int
}

object Hand {
  def of(cards: List[Card]): Hand   // sorts, which is the only invariant it carries alone
}
```

`arrangement` delegates to `Arrangement.best`, which already exists and already minimises deadwood.
`Hand` adds no rule of its own. Its cards stay in canonical order, as a meld's do, so that a
failure message and a test expectation both read the same way twice.

`Hands` goes in the same file, because it is two hands and nothing else.

**Tests.** `Deck.from` accepts any shuffling of the 52 cards and rejects four things worth pinning
as worked examples: a list with a card repeated, a list of 51, a list of 53, and a list of 52 that
holds one card twice and another not at all. That last one is the case a length check alone lets
through.

`other` is its own inverse and never returns the player it was given. `add` then `remove` returns
the hand it started with. `remove` of a card the hand does not hold returns `None`. `remove` of a
card held twice leaves one copy, which is the property that pins single removal. A hand's deadwood
agrees with the arrangement of its cards. `Hands.updated` changes one player's hand and leaves the
other alone.

## 2. The state types and the opening

```
modules/core/src/main/scala/wiggly/gin/core/domain/Move.scala
modules/core/src/main/scala/wiggly/gin/core/domain/GameError.scala
modules/core/src/main/scala/wiggly/gin/core/domain/GameState.scala
modules/core/src/test/scala/wiggly/gin/gen/GameGen.scala
modules/core/src/test/scala/wiggly/gin/core/domain/DealSuite.scala
modules/core/src/test/scala/wiggly/gin/core/domain/OpeningSuite.scala
```

`GameState.scala` holds `Table`, `Phase`, `Outcome`, the two states, `deal` and the transition
function. Keeping `Move`, `GameError`, `Player` and `Hand` in their own files is what keeps that
file readable.

```scala
def deal(deck: Deck): GameState   // total: no dealer argument, and no malformed deck
def apply(state: GameState, player: Player, move: Move): Either[GameError, GameState]
```

`deal` produces a state in `UpcardOffered(NonDealer)` rather than a state of its own. A dealt round
with no move out of it would be a state that exists only to be left immediately.

`GameGen` starts here with a shuffled deck, a dealt round, and `legalMoves`. Every later stage
builds its generators on those three.

**Tests.** The deal gives ten cards to each player, one to the discard pile and 31 to the stock,
and the four piles together are the 52 cards of the deck. The opening ceremony as worked examples,
one per branch: the non-dealer takes, the non-dealer passes and the dealer takes, both pass, and a
knock on the very first move. Rejections: `DrawStock` while the upcard is on offer, `DrawDiscard`
on the opening draw, and `Pass` once the opening has settled.

## 3. The turn cycle

```
modules/core/src/test/scala/wiggly/gin/core/domain/TurnSuite.scala
```

Draw, discard and the turn passing over. No new files, because the transition function written in
stage 2 grows to cover `AwaitingDraw` and `AwaitingDiscard`.

**Tests.** Conservation is the workhorse, and it is the same shape as the partition invariant that
step 2 leans on: after any legal move, the stock, the discard pile and both hands together are
still exactly the 52 dealt cards. No card is invented, dropped or played twice. With `Deck` guarding
the entrance, this is the property that carries every remaining guarantee about duplicates.

It must enumerate those four places and no others. `AwaitingDiscard` also holds `taken`, and that
card is in the player's hand as well, so a check that sweeps up every card-shaped field in the state
counts 53 and fails. `taken` names a card for a guard to read. It is not a place a card sits.

Five more properties sit alongside it:

- A player holds ten cards between turns and eleven in `AwaitingDiscard`.
- The turn passes to the other player after a discard, and at no other time.
- A move by the player off turn is refused, whatever the move is.
- Every live state offers at least one legal move, so the round cannot deadlock.
- A random walk of legal moves always reaches `Finished` inside the moves a 31-card stock allows.

Worked examples: a discard of a card the player does not hold, and a discard of the card just taken
from the pile.

## 4. Knock, gin and the dead round

```
modules/core/src/test/scala/wiggly/gin/core/domain/KnockSuite.scala
modules/core/src/test/scala/wiggly/gin/core/domain/DeadRoundSuite.scala
```

**Tests.** A knock that leaves exactly ten deadwood is accepted and one that leaves eleven is
refused. A knock with nothing left over records zero deadwood, which is how the finished state says
gin. The finished state keeps both hands, so a property can confirm that the cards step 4 will
score are the cards the players held. A finished round refuses every move.

The dead round: a discard that leaves two cards in the stock finishes the round with no winner, and
a knock on that same discard finishes it with one instead. Both are worked examples, because the
boundary is a rule rather than a derivation.

## Order of work

Test first throughout, in the order above, because each stage constrains the shape of the next.
Five commits, each green on its own:

1. `Deck` becomes a validated type, with the generators and the `Arrangement.best` comment following.
2. `Player` and `Hand`.
3. The state types, `deal` and the opening ceremony.
4. The turn cycle.
5. Knock, gin and the dead round.

`Deck` goes first and alone because it changes code that already exists and already has tests,
which is a different concern from adding new types beside it.

The amendment to `ROADMAP.md` rides with the first commit, because step 3 there still lists a
layoff as a move and no longer needs to.

Before handing the work over, run the gate from `CLAUDE.md`:

```bash
SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test
```

The build also gates coverage at 85% of statements and 50% of branches across both modules, so run
`sbt coverageAll` once the fifth commit lands.

## Deliberately left out

`PlayerView`, the redacted state a player is allowed to see, is step 5. It is worth knowing it is
coming, because it reads a `GameState` and must never be one. Keeping the stock a plain list, in
the order it will be drawn in, is what makes the redaction a real piece of work rather than a
rename.

The match level is step 4. `GAME-FLOW.md` records what is settled about it and what is not.
