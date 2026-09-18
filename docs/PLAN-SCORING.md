# Plan: scoring and the match

A working plan for step 4 of the [roadmap](ROADMAP.md). The rules it implements are in
[GAME-FLOW.md](GAME-FLOW.md), which is the permanent reference. This plan does not restate a rule,
so a disagreement between the two documents is a bug in this one.

All of it is pure code in `core/domain`. There is no `F[_]` and no library but `cats-core`, which
is what the domain already depends on.

Step 4 also settles the three questions that the match section of `GAME-FLOW.md` left open. The
answers are in the table below, and the flow document gains them once the code exists.

## Decisions settled up front

| Decision | Choice | Why |
| --- | --- | --- |
| Who the players are | `Player` keeps its name and becomes `One` and `Two`, the two people of a match | A ledger adds up points per person, and the deal rotates, so a seat cannot key a total. Making `Player` the person leaves the domain with one identity type instead of two similar ones that a reader has to tell apart. |
| Who dealt | A new `Seats(dealer: Player)`, with `nonDealer` derived | This is the type that models the dealer and the non-dealer, and it holds a `Player`. One field rather than two, because two players means naming one names the other, and because `Seats(One, One)` is then not a value anybody can write. |
| Where the seats live | On `GameState`, beside `table` | The opening is the only rule that reads them, but a round that cannot say who dealt it is a round that needs a second thing carried next to it everywhere it goes. |
| Layoffs | One search over candidate groups, not a best arrangement followed by a layoff pass | A two-pass order can spend the card that bridges the defender's hand onto a knocker's run. It appears not to lose with the current value of a card, for reasons set out in stage 2, but that is an argument about arithmetic rather than a property of the code. One search does not need the argument. |
| `Defence` | A new type of three lists: own melds, cards laid off, deadwood | A laid-off card belongs to neither list of an `Arrangement`. Putting it in one would break the rule that an arrangement's two lists are the whole hand. |
| `RoundScore` | Four cases: `Dead`, `Knock`, `Gin`, `Undercut` | The match counts rounds won, and a dead round must not count as one. Naming the three ways of winning is also what makes a test read as the rule it pins, rather than as a suspicious 25. |
| The match | A ledger beside the round, not a machine that owns it | Chosen deliberately. The cost is that whoever holds a game keeps the ledger and the round in step, and step 5 is where that job lands. |
| `Match` | Splits into `InProgress` and `Finished`, with private constructors | The same split `GameState` makes, for the same reason. A finished match has nobody dealing next and no round to play, so `seats` and `played` exist on one case rather than as a guard in both. `start` and `played` are the only doors, so a `Finished` can only come from a total crossing the target. |
| What a match stores | The opening seats and the list of round scores, most recent first | Every other figure is arithmetic over those two: the totals, the rounds won, and who deals next. A stored total would be a second copy of a number the history already holds, and two copies can disagree. |
| The numbers | Gin 25, undercut 25, target 100, game bonus 100, box bonus 25 for each round won, and the winner's figure doubles if the loser finished on nothing | The usual tournament game. They are named constants on the companions so that a test pins a number in one place. |

## 1. Player and Seats

```
modules/core/src/main/scala/wiggly/gin/core/domain/Player.scala     (Player changes, Seats added)
modules/core/src/main/scala/wiggly/gin/core/domain/GameState.scala  (carries the seats)
modules/core/src/main/scala/wiggly/gin/core/domain/Hand.scala       (Hands keyed by person)
modules/core/src/test/scala/wiggly/gin/gen/GameGen.scala
modules/core/src/test/scala/wiggly/gin/core/domain/PlayerSuite.scala
```

```scala
enum Player {
  case One, Two
  def other: Player
}

final case class Seats(dealer: Player) {
  def nonDealer: Player = dealer.other
  def passed: Seats     = Seats(dealer.other)
}
```

`passed` is named after the rule it carries, which is that the deal passes to the other player
after a round.

`GameState` gains `seats` beside `table`, and `deal` takes them:

```scala
def deal(deck: Deck, seats: Seats): InProgress
```

`Phase` now names people, so `AwaitingDraw(Player.One)` says who must draw and only the opening
needs to know who dealt. `Phase.onTurn` is the one place that changes shape, because
`AwaitingOpeningDraw` still names nobody:

```scala
def onTurn(seats: Seats): Player          // on Phase
def onTurn: Player                        // on GameState.InProgress, which holds both
```

`Hands` keeps its shape and renames its two fields to `one` and `two`.

This stage changes no behaviour. Every existing suite keeps its assertions and changes only the
names it builds them from. `DealSuite`, `OpeningSuite`, `TurnSuite`, `KnockSuite` and
`DeadRoundSuite` stay green throughout, which is the check that the rename is only a rename.

**Tests.** `PlayerSuite` keeps the property that `other` is its own inverse and never returns the
player it was given. `Seats` gains the same pair: `passed` twice is where it started, and the
dealer and the non-dealer are never the same person. `GameGen` gains a generator for `Seats` and
threads it through the dealt round.

## 2. Layoffs and Defence

```
modules/core/src/main/scala/wiggly/gin/core/domain/Arrangement.scala  (select generalises)
modules/core/src/main/scala/wiggly/gin/core/domain/Defence.scala
modules/core/src/test/scala/wiggly/gin/core/domain/DefenceSuite.scala
```

A defender wants every card either melded or laid off, because both cost nothing. The bridge is the
problem. Take a knocker who lays down the run `7S 8S 9S` against a defender holding `10S 10H 10D`
and `JS`. The best arrangement of the defender's hand on its own is the set of tens, which leaves
`JS` as deadwood. `JS` cannot then reach the knocker's run, because a run is contiguous and the
only card that joins `JS` to it is `10S`, which the set has taken. `10S` is the bridge, and a first
pass that does not know what a bridge is for can spend one.

In that example the two passes still win, 10 against 20, and no arrangement of those cards loses by
going first. That holds generally with the current value of a card. Freeing a bridge out of a set
costs the two other cards of its rank, which is twice the bridge's value, and it buys the chain of
cards behind the bridge, which is contiguous in rank and so worth at most one rank more per card. A
chain long enough to pay for the set is three cards, and three cards behind a bridge is a run the
defender can meld without any layoff at all. A knocker's set gives no chain, because a set of three
is one card short and that card brings nothing behind it.

So the two-pass order is safe, and it is safe because of the value table rather than because of
anything in the code. One search removes the dependency, and it is cheaper than the paragraph
above:

```scala
private def select[A](available: List[Card], candidates: List[A])(cards: A => NonEmptyList[Card]): List[A]
```

`select` already picks the subset of candidates that melds the most value. Giving it a function to
read a candidate's cards is the whole change, and it lets the same search choose between melds and
layoffs in one pass. `Arrangement.best` passes its melds and keeps its behaviour exactly.

`Defence` passes both kinds of candidate:

```scala
final case class Defence(melds: List[Meld], layoffs: List[Card], deadwood: List[Card]) {
  def deadwoodValue: Int
}

object Defence {
  def against(hand: Hand, melds: List[Meld]): Defence
}
```

A layoff candidate is a group of the defender's cards that goes onto one meld of the knocker's. For
a run, that is the cards contiguous with either end, taken as every prefix from the joining end, so
that a defender can lay off one card without owning the next. For a set of three, it is the single
card of that rank that the set is missing. A set of four takes nothing.

Candidate order decides between two answers worth the same, exactly as it does in `Arrangement`.
Melds come first in canonical order and layoff groups after them, so a tie puts a card in the
defender's own meld rather than on the knocker's, which is what the table would look like.

**Tests.** The bridge example above as a worked example, because it is the case the search exists
for. A defender who can lay off nothing gets the same deadwood as `Arrangement.best` alone. A run
extended at both ends, and a chain laid off one card at a time.

Two properties. The three lists of a `Defence` are together exactly the hand, which is the same
shape of check as the partition invariant the rest of the domain leans on. And a defender is never
worse off than `Arrangement.best` would leave them, which is the guard that catches the search
regressing.

## 3. The round score

```
modules/core/src/main/scala/wiggly/gin/core/domain/RoundScore.scala
modules/core/src/test/scala/wiggly/gin/core/domain/RoundScoreSuite.scala
```

```scala
enum RoundScore {
  case Dead
  case Knock(player: Player, points: Int)
  case Gin(player: Player, points: Int)
  case Undercut(player: Player, points: Int)

  def winner: Option[Player]
  def points: Int
}

object RoundScore {
  val GinBonus: Int      = 25
  val UndercutBonus: Int = 25

  def of(round: GameState.Finished): RoundScore
}
```

`of` reads the outcome and both hands out of the finished round, which is why step 3 kept them.

| The round ended | The score |
| --- | --- |
| `Dead` | `Dead`. Nobody scores. |
| `Knocked`, and the knocker has no deadwood | `Gin`. The knocker takes the defender's deadwood in full, plus 25. No layoffs. |
| `Knocked`, and the defender is left above the knocker | `Knock`. The knocker takes the difference. |
| `Knocked`, and the defender is left at or below the knocker | `Undercut`. The defender takes the difference, plus 25. |

The table is the authority. Gin resolves first and never builds a `Defence`, which is how "gin
blocks layoffs" becomes an order of evaluation rather than a flag. An undercut at equal deadwood
scores nothing for the difference and the bonus on top.

**Tests.** One worked example for each row, each with a hand small enough to add up by eye. Gin
against a defender who could have laid off every card, which is the case that proves gin blocks
them. An undercut at exactly equal deadwood.

Properties. A score is never negative. A dead round has no winner and every other score has one.
The winner of a `Knock` or a `Gin` is the player the outcome names, and the winner of an `Undercut`
is the other one.

## 4. The match

```
modules/core/src/main/scala/wiggly/gin/core/domain/Match.scala
modules/core/src/test/scala/wiggly/gin/core/domain/MatchSuite.scala
```

```scala
final case class Tally(one: Int, two: Int) {
  def apply(player: Player): Int
}

sealed trait Match {
  def opening: Seats
  def rounds: List[RoundScore]
  def totals: Tally
  def roundsWon: Tally
}

object Match {
  val Target: Int    = 100
  val GameBonus: Int = 100
  val BoxBonus: Int  = 25

  def start(dealer: Player): InProgress

  final case class InProgress private (opening: Seats, rounds: List[RoundScore]) extends Match {
    def seats: Seats
    def played(score: RoundScore): Match
  }

  final case class Finished private (opening: Seats, rounds: List[RoundScore]) extends Match {
    def result: MatchResult
  }
}

final case class MatchResult(winner: Player, totals: Tally)
```

`rounds` holds the most recent first, as the discard pile does, so `played` puts one on the front.

`seats` is `opening` with the deal passed once for every round that scored. A dead round is dealt
again by the same dealer, so it passes nothing, and the rule is a fact about the list rather than a
field somebody has to remember to update.

`result` takes the running totals, adds the game bonus to the winner and a box bonus for each round
that player won, and then doubles the winner's figure if the loser finished on nothing. The
doubling never has to argue with a box bonus the loser earned, because every way of winning a round
awards at least one point, so a loser on nothing is a loser who won nothing. Only one player scores
in a round, so only one total can cross the target and `played` never chooses between two winners.

**Tests.** Worked examples for the numbers, because a property cannot say what they are: a match
won on a knock that crosses 100, the game bonus, the box bonus over several rounds, and a shutout
where the winner's figure doubles.

Properties. `played` never lowers either total. No result exists while both totals are under the
target, and one exists as soon as either reaches it. A dead round leaves the totals and the dealer
alone and lengthens the history by one. The deal passes on every scored round, so the dealer after
an even number of scored rounds is the opening dealer.

## Order of work

Test first throughout, in the order above. Five commits, each green on its own:

1. `Player`, `Seats` and the rename through step 3's code, the generators and the suites. It goes
   first and alone because it changes code that already has tests and adds no behaviour, which is a
   different concern from the scoring built on top of it.
2. `select` generalises and `Defence` arrives.
3. `RoundScore`.
4. `Match`.
5. The documents: the match section of `GAME-FLOW.md` gains the settled answers, and `ROADMAP.md`
   marks steps 3 and 4 done.

Before handing the work over, run the gate from `CLAUDE.md`:

```bash
SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test
```

The build also gates coverage at 85% of statements and 50% of branches across both modules, so run
`sbt coverageAll` once the last commit lands.

## Deliberately left out

A match does not hold the round being played. That was a choice rather than an omission, and it
leaves one job for whoever holds a game: deal each round with `match.seats`, and fold the finished
round into the ledger before dealing the next. A round records the seats it was dealt with, so the
two can be compared, but nothing in step 4 compares them. `GameService` in step 5 is the single
place that will do both, which is what keeps the pair honest.

Bonuses that depend on the shape of a match rather than its score, such as a bonus for winning
every round, are not here. The three bonuses above are the ones the game is normally played with.

Shuffling is still absent, so `Match.start` names a dealer and nothing deals a deck for it. The
port that supplies a shuffled deck arrives with step 5, along with whatever chooses who deals the
first round.
