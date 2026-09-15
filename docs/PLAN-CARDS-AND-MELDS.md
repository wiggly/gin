# Plan: cards, melds and deadwood

A working plan for steps 1 and 2 of [the roadmap](ROADMAP.md). Both are pure code in `core` with no
`IO`. Step 2 contains the only genuinely hard algorithm in the game, so most of the detail below is
about getting that one right rather than fast.

## Decisions settled up front

These are cheap now and expensive once the domain has landed on top of them.

| Decision | Choice | Why |
| --- | --- | --- |
| Domain package | `wiggly.gin.core` | Mirrors `wiggly.gin.server`. Generators stay in `wiggly.gin.gen`, as `README.md` already commits them to — a test-only sibling shared through the existing `test->test` edge. |
| `core`'s declared dependencies | Add `cats-core` by name | Steps 1–4 are pure and need only `Order`, `Show` and `NonEmptyList`. It arrives transitively through `cats-effect` today; naming it states the truth. `cats-effect` stays for the step 5 ports. |
| Ordering | Hand-written cats `Order` instances | Not `cats-laws`/`discipline`: that drags in munit, which was removed deliberately. A property that pins ordering against the deck is worth more here than the law suite anyway. |
| `Show` | `core` shows a card as `Ace of Spades` | Legible failure output without putting a wire format in the domain. A compact `AS` form belongs in the step 5 codec, in the adapter. |
| A `Hand` type | Deferred to step 3 | Step 2 needs only "some cards", and `List[Card]` is what makes the small worked examples below expressible. |
| `isGin` / `canKnock` | Deferred to steps 3–4 | Both need turn context — ten cards kept versus eleven held. Step 2 asserts on `deadwoodValue`, the primitive they would be built from. |

## 1. Cards

```
modules/core/src/main/scala/wiggly/gin/core/Suit.scala
modules/core/src/main/scala/wiggly/gin/core/Rank.scala
modules/core/src/main/scala/wiggly/gin/core/Card.scala
modules/core/src/main/scala/wiggly/gin/core/Deck.scala
modules/core/src/test/scala/wiggly/gin/gen/CardGen.scala
modules/core/src/test/scala/wiggly/gin/core/RankSuite.scala
modules/core/src/test/scala/wiggly/gin/core/CardSuite.scala
modules/core/src/test/scala/wiggly/gin/core/DeckSuite.scala
```

`Rank` carries two numbers, and keeping them apart matters:

- `order` is the run sequence. The ace is low and runs do not wrap, so `K-A-2` is not a run.
- `deadwoodValue` is the scoring value: A=1, 2–10 pip, J/Q/K=10.

Two members exist because step 2 needs them, not for their own sake:

- `Rank.next: Option[Rank]`, `None` at the king. This makes run enumeration a walk rather than
  arithmetic on `order`.
- `Order[Rank]`, `Order[Suit]`, `Order[Card]` lexicographic on `(suit, rank)`. Suits do not rank
  against each other in gin rummy; the order exists so that a collection of cards has one canonical
  form, which keeps melds comparable and test expectations stable.

`Deck.ordered` is the 52 cards suit-major. No shuffling here — step 3 takes an already-shuffled deck
so that the effect stays at the edge.

**Tests.** The deck is 52 distinct cards covering every suit/rank pair exactly once. The value table
pinned literally at its boundaries (A, 10, J, Q, K) because it is a rule, not a derivation. Properties
that `deadwoodValue` is in 1..10 and equals `order` below the jack; that `next` agrees with `order + 1`
and is `None` exactly at the king; that sorting the shuffled deck reproduces `Deck.ordered`; and that
distinct cards never compare equal.

## 2. Melds

```
modules/core/src/main/scala/wiggly/gin/core/Meld.scala
modules/core/src/test/scala/wiggly/gin/gen/MeldGen.scala
modules/core/src/test/scala/wiggly/gin/core/MeldSuite.scala
```

An invalid meld should be unrepresentable: private constructors, smart constructors returning
`Option`, and `cards` always in canonical order. Each kind carries its cards rather than a structural
encoding such as `Run(suit, lowest, length)` — the constructor enforces the invariant either way, and
the cards are what every consumer downstream actually wants, layoffs in step 4 included.

```scala
sealed trait Meld {
  def cards: NonEmptyList[Card]
  def deadwoodValue: Int
}

object Meld {
  final case class Set private (cards: NonEmptyList[Card]) extends Meld  // 3-4 of one rank
  final case class Run private (cards: NonEmptyList[Card]) extends Meld  // 3+ in sequence, one suit

  def from(cards: List[Card]): Option[Meld]   // delegating to Set.from and Run.from
}
```

**Tests.** Every generated meld round-trips through `from`, and `from` is insensitive to input order.
Rejections pinned as worked examples: two of a rank; a repeated suit in a set; `Q-K-A` and `K-A-2`; a
mixed-suit run; a run with a gap; a two-card run. A meld's value is the sum of its cards'.

## 3. Best arrangement

```
modules/core/src/main/scala/wiggly/gin/core/Arrangement.scala
modules/core/src/test/scala/wiggly/gin/core/ArrangementSuite.scala
```

`Arrangement(melds, deadwood)` with `deadwoodValue`, and `Arrangement.best(hand)` returning the
arrangement that minimises it. `best` is total over any number of cards, not just ten or eleven: a
partial hand is a legitimate question and it keeps the worked examples small.

Brute force, in two stages.

**Enumerate candidates** — every valid meld that is a subset of the hand. Sets: group by rank, take
every three-subset and the four-subset. Runs: group by suit, find the maximal consecutive stretches,
and emit *every sub-window of length three or more* within each stretch.

> The sub-windows are not an optimisation detail; enumerating only maximal runs is wrong. Given
> `4D 5D 6D 7D 7H 7C`, the maximal run `4D-7D` leaves `7H 7C` for 14, while `4D 5D 6D` plus the set
> `7D 7H 7C` is gin. This wants to be a test before it is a line of code.

**Search** — candidates in a fixed canonical order, recursing take-or-skip, taking a meld only while
its cards are still available, keeping the selection that melds the most value. Deadwood is the
remainder. Ties resolve to whichever selection the canonical order reaches first, which is what makes
`best` deterministic and therefore assertable; that is part of the contract, not an accident.

A hand is ten or eleven cards, so the search space is small. Memoisation on
`(candidateIndex, availableCards)` is the escape hatch if a profile ever asks for one. Per the
roadmap: a correct slow version beats a clever wrong one.

**Tests.** The partition invariant is the workhorse — `melds.flatMap(_.cards) ++ deadwood` is a
permutation of the hand, so no card is invented, dropped or used twice. Alongside it: every returned
meld is valid; and an optimality property where a generator builds a hand *by construction* from known
melds plus known loose cards and yields its deadwood as an upper bound, so the bound comes from
outside the search rather than from the code under test. Also that adding a card costs at most its own
value — note the reverse does not hold, since a card can complete a meld and cut deadwood to zero.

Worked examples: the contested card (`8D 9D 10D 8S 8H` — the run wins, leaving 16, not the set leaving
19); the sub-window hand above; gin; the knock boundary at exactly 10 and at 11; and a hand with no
meld at all, whose deadwood is simply the sum of its cards.

## Order of work

Test first throughout, in the order the sections above list, since each piece constrains the shape of
the next. Three commits, each green on its own: the card model and deck; melds and their validation;
the arrangement search. The `build.sbt` change rides with the first.

Before handing over, the gate from `CLAUDE.md`:

```bash
SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test
```

## Deliberately left out

`Meld.extend(card)` — the layoff operation — is a natural method on `Meld`, and step 4 will want it.
It has no caller until scoring exists, and which melds a knocker's deadwood may attach to is a step 4
question. Worth knowing it is coming, so that `Run` stays easy to extend at either end.
