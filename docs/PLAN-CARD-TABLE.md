# Card table implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** The table draws cards as pictures, and a player plays with the mouse. They select a card
and then act on it, draw by clicking a pile, and put their hand in any order.

**Architecture:** A pure `Holding` type in `ui` holds the order of the hand, the selected card and
the card being dragged. A new `Model.Playing` case holds it beside the unchanged `PlayerView.InPlay`.
A new `Cards` object draws faces and backs as inline SVG with Tyrian's `SVG` tags. `View` sends
`Msg.Play` only with moves from `Moves.available`, so the rule of which moves are legal stays in
`core`.

**Tech Stack:** Scala 3.9.0, Scala.js 1.22.0, Tyrian 0.14.0, scalajs-dom 2.8.0, weaver 0.13.0 with
weaver-scalacheck, sbt 1.13.0. No new dependencies.

**Spec:** [`docs/SPEC-CARD-TABLE.md`](SPEC-CARD-TABLE.md)

**Status:** Implemented on branch `web-ui`. Four steps are not done, because they need a person at a
browser: Task 3 Step 8, Task 4 Step 6, Task 5 Step 5 and the browser play-through in Task 6 Step 1.
The image was built and started, and `/health`, `/play`, `/gin.css` and `/main.js` answered 200.

## Global Constraints

- Scala 3.9.0, sbt 1.13.0, JDK 25. Tyrian 0.14.0. Add no library.
- Braces delimit scope. Never indentation syntax. This covers every definition, `match` case and
  `if` body.
- Tests are weaver suites, objects named `*Suite`, property-based by default with
  `weaver-scalacheck`. `pureTest` is for a rule worth pinning literally. Never munit.
- Generators stay in `modules/core/src/test/scala/wiggly/gin/gen/`. Pass an explicit `Gen` to
  `forall` rather than relying on an implicit `Arbitrary`.
- Comments say why, never what. Default to none.
- `scalacOptions` are managed by sbt-tpolecat. Never set them directly. The gate adds `-Werror`, so
  an unused import fails it.
- Commit messages are one subject line and nothing else. No `Co-Authored-By` and no generated-by
  footer.
- Each commit builds and tests green on its own.
- `Msg.Play` is the only message that reaches the server, and its move always comes from
  `Moves.available`.
- The view in the model stays exactly as the server sent it. The browser's own state lives in
  `Holding` and nowhere else.
- No animation past a CSS transition on the lift. The column stays at its current width.
- The gate, before handing the work over:
  `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`

A full `sbt test` takes about 90 seconds from cold. That is not a hang. Do not pipe `sbt` through
`grep` while you watch it. If a compile fails with `NoSuchFileException` into `classes.bak`, run it
again with `clean`. That is an incremental-compile race, not a code error.

The `core` cross-project has the sbt ids `coreJVM` and `coreJS`. `ui` reaches the generators in
`core`'s tests through the `test->test` edge on `protocol`. A new generator there is therefore
visible to the `ui` suites with no build change.

## File structure

| File | Responsibility |
| --- | --- |
| `modules/core/src/test/scala/wiggly/gin/gen/ViewGen.scala` | Create. Views of a round in play, for the `ui` suites. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Holding.scala` | Create. The hand as the browser lays it out, and `Sorting`. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Model.scala` | Gains `Playing`. `Seated` narrows to the views with no hand. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Msg.scala` | Gains `Toggle`, `Sort`, `Lift`, `Drop` and `Hover`. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Update.scala` | Routes a view to `Seated` or `Playing`, and applies the hand messages. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Cards.scala` | Create. A card face and a card back as SVG, and the names of a card. |
| `modules/ui/src/main/scala/wiggly/gin/ui/View.scala` | The table, drawn with `Cards`, with clickable piles and hand. |
| `modules/ui/src/main/scala/wiggly/gin/ui/Main.scala` | Subscribes in `Playing` too. |
| `modules/ui/src/test/scala/wiggly/gin/ui/HoldingSuite.scala` | Create. The `Holding` properties. |
| `modules/ui/src/test/scala/wiggly/gin/ui/CardsSuite.scala` | Create. The names a card is drawn with. |
| `modules/ui/src/test/scala/wiggly/gin/ui/Handlers.scala` | Create. Reads the messages that a rendered page sends, with no browser. |
| `modules/ui/src/test/scala/wiggly/gin/ui/UpdateSuite.scala` | Cases for `Playing` and the hand messages. |
| `modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala` | Cases for the pictures, the clicks and the drops. |
| `modules/server/src/main/resources/web/gin.css` | Card art, the fans, the lift and the meld tints. |
| `modules/server/src/main/resources/web/index.html` | The one pattern every card back fills with. |
| `build.sbt` | `ui/Cards` joins the coverage exclusions beside `ui/View`. |

---

### Task 1: The `Holding` type

A pure type and its properties. Nothing draws it yet.

**Files:**
- Create: `modules/core/src/test/scala/wiggly/gin/gen/ViewGen.scala`
- Create: `modules/ui/src/main/scala/wiggly/gin/ui/Holding.scala`
- Test: `modules/ui/src/test/scala/wiggly/gin/ui/HoldingSuite.scala`

**Interfaces:**
- Consumes: `GameGen.inPlay: Gen[Game.InPlay]`, `GameGen.deck: Gen[Deck]`,
  `PlayerView.of(game, you): PlayerView`, `Moves.available(view): List[Move]`,
  `Game.played(player, move, next): Either[GameFault, Game]`.
- Produces:
  - `ViewGen.viewOf(game: Game.InPlay, you: Player): PlayerView.InPlay`
  - `ViewGen.held(view: PlayerView.InPlay): List[Card]`, the cards in the server's order.
  - `ViewGen.inPlay: Gen[PlayerView.InPlay]`, either player's view.
  - `ViewGen.discarding: Gen[PlayerView.InPlay]`, the view of a player who owes a discard.
  - `ViewGen.turn: Gen[(PlayerView.InPlay, PlayerView.InPlay)]`, one player's view before and
    after a move.
  - `enum Sorting { case ByMelds, BySuit, ByRank }`
  - `Holding.of(view: PlayerView.InPlay): Holding`
  - `Holding#order: List[Card]`, `Holding#selected: Option[Card]`, `Holding#dragging: Option[Card]`
  - `Holding#reconcile(view: PlayerView.InPlay): Holding`
  - `Holding#sorted(by: Sorting, view: PlayerView.InPlay): Holding`
  - `Holding#toggled(card: Card, view: PlayerView.InPlay): Holding`
  - `Holding#lifted(card: Card): Holding`
  - `Holding#moved(before: Option[Card]): Holding`

- [x] **Step 1: Write the view generators**

Create `modules/core/src/test/scala/wiggly/gin/gen/ViewGen.scala`:

```scala
package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.domain.*

/** A round in play as one player sees it. */
object ViewGen {

  def viewOf(game: Game.InPlay, you: Player): PlayerView.InPlay = {
    PlayerView.of(game, you) match {
      case view: PlayerView.InPlay => view
      case other                   => sys.error(s"a game in play was seen as $other")
    }
  }

  def held(view: PlayerView.InPlay): List[Card] =
    view.arrangement.melds.flatMap(_.cards.toList) ++ view.arrangement.deadwood

  val inPlay: Gen[PlayerView.InPlay] = for {
    game <- GameGen.inPlay
    you  <- Gen.oneOf(Player.values.toList)
  } yield viewOf(game, you)

  /** Built rather than filtered. A random walk owes a discard about one step in three, and a
    * filter that threw the rest away would starve the properties that need a card to choose.
    */
  val discarding: Gen[PlayerView.InPlay] = for {
    game <- GameGen.inPlay
    next <- GameGen.deck
  } yield {
    val drawn = game.round.phase match {
      case _: Phase.AwaitingDiscard => game
      case _                        => drawFrom(game, next)
    }

    viewOf(drawn, drawn.round.onTurn)
  }

  /** The mover may be either player, so the viewer sees their own move or the other player's. */
  val turn: Gen[(PlayerView.InPlay, PlayerView.InPlay)] = for {
    game <- GameGen.inPlay
    you  <- Gen.oneOf(Player.values.toList)
    move <- Gen.oneOf(Moves.available(viewOf(game, game.round.onTurn)))
    next <- GameGen.deck
  } yield {
    val after = game.played(game.round.onTurn, move, next) match {
      case Right(playing: Game.InPlay) => playing
      // The match ended, so there is no later round to see. The same view twice is still a turn.
      case _ => game
    }

    (viewOf(game, you), viewOf(after, you))
  }

  private def drawFrom(game: Game.InPlay, next: Deck): Game.InPlay = {
    val onTurn = game.round.onTurn
    val draw   = Moves.available(viewOf(game, onTurn)).collectFirst {
      case move @ (Move.DrawStock | Move.DrawDiscard) => move
    }

    draw.flatMap(game.played(onTurn, _, next).toOption) match {
      case Some(drawn: Game.InPlay) => drawn
      case other                    => sys.error(s"drawing did not leave a discard owed: $other")
    }
  }
}
```

- [x] **Step 2: Write the failing `Holding` properties**

Create `modules/ui/src/test/scala/wiggly/gin/ui/HoldingSuite.scala`:

```scala
package wiggly.gin.ui

import cats.Show
import cats.syntax.all.*
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.*
import wiggly.gin.gen.ViewGen
import wiggly.gin.gen.ViewGen.held

object HoldingSuite extends SimpleIOSuite with Checkers {

  /** What a player can do to their hand, as data, so that one property can try a run of them. */
  private enum Op {
    case Toggle(card: Card)
    case Sort(by: Sorting)
    case Lift(card: Card)
    case Drop(before: Option[Card])
  }

  private given Show[PlayerView.InPlay] = Show.fromToString
  private given Show[Op]                = Show.fromToString

  private val views: Gen[PlayerView.InPlay] = Gen.oneOf(ViewGen.inPlay, ViewGen.discarding)

  private val runs: Gen[(PlayerView.InPlay, List[Op])] = views.flatMap { view =>
    val card = Gen.oneOf(held(view))
    val op   = Gen.oneOf(
      card.map(Op.Toggle(_)),
      Gen.oneOf(Sorting.values.toList).map(Op.Sort(_)),
      card.map(Op.Lift(_)),
      Gen.option(card).map(Op.Drop(_))
    )

    Gen.listOf(op).map(view -> _)
  }

  private val choices: Gen[(PlayerView.InPlay, Card)] =
    views.flatMap(view => Gen.oneOf(held(view)).map(view -> _))

  private val pairs: Gen[(PlayerView.InPlay, Card, Card)] = for {
    view   <- ViewGen.inPlay
    card   <- Gen.oneOf(held(view))
    target <- Gen.oneOf(held(view))
  } yield (view, card, target)

  private def played(view: PlayerView.InPlay, run: List[Op]): Holding = {
    run.foldLeft(Holding.of(view)) { (holding, op) =>
      op match {
        case Op.Toggle(card)  => holding.toggled(card, view)
        case Op.Sort(by)      => holding.sorted(by, view)
        case Op.Lift(card)    => holding.lifted(card)
        case Op.Drop(before)  => holding.moved(before)
      }
    }
  }

  private def legal(view: PlayerView.InPlay)(card: Card): Boolean =
    Moves.available(view).contains(Move.Discard(card))

  private def chosen(view: PlayerView.InPlay): Holding = {
    val first = Moves.available(view).collectFirst { case Move.Discard(card) => card }

    first.fold(Holding.of(view))(Holding.of(view).toggled(_, view))
  }

  test("a hand starts in the server's order with nothing chosen") {
    forall(ViewGen.inPlay) { view =>
      val holding = Holding.of(view)

      expect.same(held(view), holding.order) and
        expect(holding.selected.isEmpty) and
        expect(holding.dragging.isEmpty)
    }
  }

  test("whatever the player does, the hand holds exactly the cards the server dealt") {
    forall(runs) { case (view, run) =>
      expect.same(held(view).sorted, played(view, run).order.sorted)
    }
  }

  test("whatever the player does, only a legal discard is selected") {
    forall(runs) { case (view, run) =>
      expect(played(view, run).selected.forall(legal(view)))
    }
  }

  test("whatever the player does, a lifted card is one the player holds") {
    forall(runs) { case (view, run) =>
      val holding = played(view, run)

      expect(holding.dragging.forall(holding.order.contains))
    }
  }

  test("a click selects a card exactly when it is a legal discard") {
    forall(choices) { case (view, card) =>
      val selected = Holding.of(view).toggled(card, view).selected

      expect.same(Option.when(legal(view)(card))(card), selected)
    }
  }

  test("a second click on the same card puts the hand back as it was") {
    forall(choices) { case (view, card) =>
      val start = Holding.of(view)

      expect.same(start, start.toggled(card, view).toggled(card, view))
    }
  }

  test("a new view keeps the player's order and puts new cards last") {
    forall(ViewGen.turn) { case (before, after) =>
      val mine = Holding.of(before).sorted(Sorting.ByRank, before)
      val next = mine.reconcile(after)
      val kept = mine.order.filter(held(after).contains)

      expect.same(kept, next.order.take(kept.size)) and
        expect(next.order.drop(kept.size).forall(card => !mine.order.contains(card)))
    }
  }

  test("a new view holds exactly the cards it deals") {
    forall(ViewGen.turn) { case (before, after) =>
      val next = Holding.of(before).sorted(Sorting.BySuit, before).reconcile(after)

      expect.same(held(after).sorted, next.order.sorted)
    }
  }

  test("a new view drops a selection that is no longer legal") {
    forall(ViewGen.turn) { case (before, after) =>
      expect(chosen(before).reconcile(after).selected.forall(legal(after)))
    }
  }

  test("the same view again keeps the selection") {
    forall(ViewGen.discarding) { view =>
      val holding = chosen(view)

      expect.same(holding.selected, holding.reconcile(view).selected)
    }
  }

  test("a new view keeps a lifted card that is still held") {
    forall(ViewGen.turn) { case (before, after) =>
      val lifted = Holding.of(before).lifted(held(before).head)
      val next   = lifted.reconcile(after)

      expect.same(lifted.dragging.filter(held(after).contains), next.dragging)
    }
  }

  test("a lifted card dropped on another sits directly before it") {
    forall(pairs) { case (view, card, target) =>
      val order = Holding.of(view).lifted(card).moved(Some(target)).order

      if (card === target) {
        expect.same(held(view), order)
      } else {
        expect.same(order.indexOf(target) - 1, order.indexOf(card))
      }
    }
  }

  test("a lifted card dropped at the end of the row goes last") {
    forall(choices) { case (view, card) =>
      val moved = Holding.of(view).lifted(card).moved(None)

      expect.same(Some(card), moved.order.lastOption) and expect(moved.dragging.isEmpty)
    }
  }

  test("a drop with nothing lifted changes nothing") {
    forall(choices) { case (view, card) =>
      expect.same(Holding.of(view), Holding.of(view).moved(Some(card)))
    }
  }

  test("sorting by melds gives back the server's order") {
    forall(runs) { case (view, run) =>
      expect.same(held(view), played(view, run).sorted(Sorting.ByMelds, view).order)
    }
  }

  test("sorting by suit groups each suit, low to high") {
    forall(runs) { case (view, run) =>
      val holding = played(view, run)

      expect.same(holding.order.sorted, holding.sorted(Sorting.BySuit, view).order)
    }
  }

  test("sorting by rank puts the ranks in order") {
    forall(runs) { case (view, run) =>
      val ranks = played(view, run).sorted(Sorting.ByRank, view).order.map(_.rank.order)

      expect.same(ranks.sorted, ranks)
    }
  }
}
```

- [x] **Step 3: Run it and make sure it fails**

Run: `sbt "ui/testOnly wiggly.gin.ui.HoldingSuite"`

Expected: a compile failure, `Not found: Holding` and `Not found: Sorting`.

- [x] **Step 4: Write `Holding`**

Create `modules/ui/src/main/scala/wiggly/gin/ui/Holding.scala`:

```scala
package wiggly.gin.ui

import cats.syntax.all.*
import wiggly.gin.core.domain.{Arrangement, Card, Move, Moves, PlayerView}

enum Sorting {
  case ByMelds, BySuit, ByRank
}

/** The player's hand as the browser lays it out.
  *
  * The server decides which cards are held. This decides only where they sit and which one the
  * player is pointing at, which is why it sits beside the view rather than inside it. Every way
  * to make one keeps `order` to exactly the cards the last view dealt, and a selected or lifted
  * card to one of them.
  */
final case class Holding private (
    order: List[Card],
    selected: Option[Card],
    dragging: Option[Card]
) {

  def reconcile(view: PlayerView.InPlay): Holding = {
    val held  = Holding.cardsIn(view.arrangement)
    val kept  = order.filter(held.contains)
    val added = held.filterNot(order.contains)

    new Holding(
      kept ++ added,
      selected.filter(Holding.discardable(view)),
      dragging.filter(held.contains)
    )
  }

  def sorted(by: Sorting, view: PlayerView.InPlay): Holding = {
    val arranged = by match {
      case Sorting.ByMelds => Holding.cardsIn(view.arrangement)
      case Sorting.BySuit  => order.sorted
      case Sorting.ByRank  => order.sortBy(card => (card.rank.order, card.suit.ordinal))
    }

    copy(order = arranged)
  }

  def toggled(card: Card, view: PlayerView.InPlay): Holding = {
    if (selected.contains(card)) {
      copy(selected = None)
    } else if (Holding.discardable(view)(card)) {
      copy(selected = Some(card))
    } else {
      this
    }
  }

  def lifted(card: Card): Holding =
    if (order.contains(card)) copy(dragging = Some(card)) else this

  def moved(before: Option[Card]): Holding = {
    dragging match {
      case None                              => this
      case Some(card) if before.contains(card) => copy(dragging = None)
      case Some(card)                        => {
        val rest         = order.filterNot(_ === card)
        val at           = before.map(rest.indexOf).filter(_ >= 0).getOrElse(rest.size)
        val (head, tail) = rest.splitAt(at)

        copy(order = head ++ (card :: tail), dragging = None)
      }
    }
  }
}

object Holding {

  def of(view: PlayerView.InPlay): Holding = new Holding(cardsIn(view.arrangement), None, None)

  private def cardsIn(arrangement: Arrangement): List[Card] =
    arrangement.melds.flatMap(_.cards.toList) ++ arrangement.deadwood

  private def discardable(view: PlayerView.InPlay)(card: Card): Boolean =
    Moves.available(view).contains(Move.Discard(card))
}
```

`cardsIn` repeats `ViewGen.held` on purpose. One is the production rule, and the other is what the
tests check it against.

- [x] **Step 5: Run the suite and make sure it passes**

Run: `sbt "ui/testOnly wiggly.gin.ui.HoldingSuite"`

Expected: every test passes. If `ViewGen.discarding` fails with "drawing did not leave a discard
owed", print the game it names. That error means that a draw ended the round, and the generator
needs to skip that game rather than draw from it.

- [x] **Step 6: Run the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`

Expected: green. If `scalafmtCheckAll` fails, run `sbt scalafmtAll` and run the gate again.

- [x] **Step 7: Commit**

```bash
git add modules/core/src/test/scala/wiggly/gin/gen/ViewGen.scala \
  modules/ui/src/main/scala/wiggly/gin/ui/Holding.scala \
  modules/ui/src/test/scala/wiggly/gin/ui/HoldingSuite.scala
git commit -m "Lay out a player's hand in an order of their own"
```

---

### Task 2: `Model.Playing` and the hand messages

The model gets a case for a round in play, and `Update` applies the hand messages. The screens look
exactly as they did.

**Files:**
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Model.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Msg.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Update.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/Main.scala`
- Test: `modules/ui/src/test/scala/wiggly/gin/ui/UpdateSuite.scala`
- Test: `modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala`

**Interfaces:**
- Consumes: everything Task 1 produces.
- Produces:
  - `Model.Seated(credentials: Credentials, view: PlayerView.AwaitingOpponent | PlayerView.Over, error: Option[String])`
  - `Model.Playing(credentials: Credentials, view: PlayerView.InPlay, holding: Holding, error: Option[String])`
  - `Msg.Toggle(card: Card)`, `Msg.Sort(by: Sorting)`, `Msg.Lift(card: Card)`,
    `Msg.Drop(before: Option[Card])`, `Msg.Hover`
  - `View.table(view: PlayerView.InPlay, error: Option[String])`, still private, still drawn as
    text. Task 3 and Task 4 change it.

- [x] **Step 1: Write the failing update tests**

Replace `modules/ui/src/test/scala/wiggly/gin/ui/UpdateSuite.scala` with:

```scala
package wiggly.gin.ui

import cats.Show
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.*
import wiggly.gin.core.port.Credentials
import wiggly.gin.gen.ViewGen
import wiggly.gin.gen.ViewGen.held

object UpdateSuite extends SimpleIOSuite with Checkers {

  private given Show[PlayerView.InPlay] = Show.fromToString
  private given Show[Msg]               = Show.fromToString

  private val seat = Credentials(GameId("g1"), Player.One, Token("t1"))

  private val waiting = PlayerView.AwaitingOpponent(GameId("g1"), Player.One)

  private val handMessages: Gen[(PlayerView.InPlay, Card, Msg)] = for {
    view <- Gen.oneOf(ViewGen.inPlay, ViewGen.discarding)
    card <- Gen.oneOf(held(view))
    msg  <- Gen.oneOf(
      Msg.Toggle(card),
      Msg.Sort(Sorting.ByRank),
      Msg.Lift(card),
      Msg.Drop(Some(card)),
      Msg.Drop(None),
      Msg.Hover
    )
  } yield (view, card, msg)

  private def chosen(view: PlayerView.InPlay): Holding = {
    val first = Moves.available(view).collectFirst { case Move.Discard(card) => card }

    first.fold(Holding.of(view))(Holding.of(view).toggled(_, view))
  }

  pureTest("taking a seat moves off the start screen") {
    val (model, _) = Update(Model.Start("", None), Msg.Took(seat))
    expect.same(Model.Connecting(seat), model)
  }

  pureTest("the first view seats the player") {
    val (model, _) = Update(Model.Connecting(seat), Msg.Saw(waiting))
    expect.same(Model.Seated(seat, waiting, None), model)
  }

  pureTest("a later view replaces the view and clears the last error") {
    val (model, _) = Update(Model.Seated(seat, waiting, Some("stale")), Msg.Saw(waiting))
    expect.same(Model.Seated(seat, waiting, None), model)
  }

  pureTest("a failure keeps the seat and shows the reason") {
    val (model, _) = Update(Model.Seated(seat, waiting, None), Msg.Failed("nope"))
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

  test("a round in play seats the player at the table, in the server's order") {
    forall(ViewGen.inPlay) { view =>
      val (model, _) = Update(Model.Connecting(seat), Msg.Saw(view))
      expect.same(Model.Playing(seat, view, Holding.of(view), None), model)
    }
  }

  test("a round starting while the player waits moves them to the table") {
    forall(ViewGen.inPlay) { view =>
      val (model, _) = Update(Model.Seated(seat, waiting, None), Msg.Saw(view))
      expect.same(Model.Playing(seat, view, Holding.of(view), None), model)
    }
  }

  test("a later view keeps the order the player chose and clears the last error") {
    forall(ViewGen.turn) { case (before, after) =>
      val mine       = Holding.of(before).sorted(Sorting.ByRank, before)
      val (model, _) = Update(Model.Playing(seat, before, mine, Some("stale")), Msg.Saw(after))
      expect.same(Model.Playing(seat, after, mine.reconcile(after), None), model)
    }
  }

  test("a hand message changes the holding and nothing else") {
    forall(handMessages) { case (view, card, msg) =>
      val holding  = Holding.of(view).lifted(card)
      val expected = msg match {
        case Msg.Toggle(chosen) => holding.toggled(chosen, view)
        case Msg.Sort(by)       => holding.sorted(by, view)
        case Msg.Lift(lifted)   => holding.lifted(lifted)
        case Msg.Drop(before)   => holding.moved(before)
        case _                  => holding
      }
      val (model, _) = Update(Model.Playing(seat, view, holding, Some("kept")), msg)

      expect.same(Model.Playing(seat, view, expected, Some("kept")), model)
    }
  }

  test("a hand message away from the table changes nothing") {
    forall(handMessages) { case (_, _, msg) =>
      val away = List(Model.Start("g2", None), Model.Connecting(seat), Model.Seated(seat, waiting, None))

      expect(away.forall(model => Update(model, msg)._1 == model))
    }
  }

  test("a move keeps the holding and clears the last error") {
    forall(ViewGen.discarding) { view =>
      val holding    = chosen(view)
      val move       = Moves.available(view).head
      val (model, _) = Update(Model.Playing(seat, view, holding, Some("stale")), Msg.Play(move))

      expect.same(Model.Playing(seat, view, holding, None), model)
    }
  }

  test("a refused move keeps the selection") {
    forall(ViewGen.discarding) { view =>
      val holding    = chosen(view)
      val (model, _) = Update(Model.Playing(seat, view, holding, None), Msg.Failed("nope"))

      expect.same(Model.Playing(seat, view, holding, Some("nope")), model)
    }
  }
}
```

In `modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala`, change the type of `waiting` so that
it fits the narrowed `Seated`:

```scala
  private val waiting = PlayerView.AwaitingOpponent(GameId("g1"), Player.One)
```

- [x] **Step 2: Run it and make sure it fails**

Run: `sbt "ui/testOnly wiggly.gin.ui.UpdateSuite"`

Expected: a compile failure, `Playing is not a member of object Model` and `Toggle is not a member
of object Msg`.

- [x] **Step 3: Write the model and the messages**

Replace `modules/ui/src/main/scala/wiggly/gin/ui/Model.scala` with:

```scala
package wiggly.gin.ui

import wiggly.gin.core.domain.PlayerView
import wiggly.gin.core.port.Credentials

/** What the browser holds.
  *
  * Every view is kept exactly as the server sent it. The server owns the redaction, and a client
  * that re-models a view is a second author of it. The one thing the browser adds is the layout of
  * a hand, and only a round in play has a hand, so only `Playing` carries one.
  */
enum Model {

  case Start(joining: String, error: Option[String])

  /** A seat is held but no view has arrived for it. The server publishes a game's current state to
    * a new watcher at once, so this is brief, but it is a state the model can be in.
    */
  case Connecting(credentials: Credentials)

  case Seated(
      credentials: Credentials,
      view: PlayerView.AwaitingOpponent | PlayerView.Over,
      error: Option[String]
  )

  case Playing(
      credentials: Credentials,
      view: PlayerView.InPlay,
      holding: Holding,
      error: Option[String]
  )
}
```

Replace `modules/ui/src/main/scala/wiggly/gin/ui/Msg.scala` with:

```scala
package wiggly.gin.ui

import wiggly.gin.core.domain.{Card, Move, PlayerView}
import wiggly.gin.core.port.Credentials

/** What the person did, and what came back.
  *
  * A failure is a message like any other, because the model has somewhere to put a reason and
  * nowhere to put a throwable.
  */
enum Msg {
  case JoiningChanged(id: String)
  case Create
  case Join
  case Play(move: Move)
  case Took(credentials: Credentials)
  case Saw(view: PlayerView)
  case Failed(reason: String)
  case Toggle(card: Card)
  case Sort(by: Sorting)
  case Lift(card: Card)
  case Drop(before: Option[Card])

  /** A drop target has to cancel `dragover`, and a Tyrian handler has to return a message to do
    * it. This is that message, and nothing handles it.
    */
  case Hover
}
```

- [x] **Step 4: Write the update function**

Replace `modules/ui/src/main/scala/wiggly/gin/ui/Update.scala` with:

```scala
package wiggly.gin.ui

import cats.effect.IO
import tyrian.Cmd
import wiggly.gin.core.domain.PlayerView
import wiggly.gin.core.port.Credentials

/** The pure half of the application: a message and a model in, a model and a command out. */
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
        (seat(credentials, view, None), Cmd.None)

      case (Model.Seated(credentials, _, _), Msg.Saw(view)) =>
        (seat(credentials, view, None), Cmd.None)

      case (Model.Playing(credentials, _, holding, _), Msg.Saw(view)) =>
        (seat(credentials, view, Some(holding)), Cmd.None)

      case (Model.Playing(credentials, view, holding, _), Msg.Play(move)) =>
        (Model.Playing(credentials, view, holding, None), Api.play(credentials, move))

      case (playing: Model.Playing, Msg.Toggle(card)) =>
        (playing.copy(holding = playing.holding.toggled(card, playing.view)), Cmd.None)

      case (playing: Model.Playing, Msg.Sort(by)) =>
        (playing.copy(holding = playing.holding.sorted(by, playing.view)), Cmd.None)

      case (playing: Model.Playing, Msg.Lift(card)) =>
        (playing.copy(holding = playing.holding.lifted(card)), Cmd.None)

      case (playing: Model.Playing, Msg.Drop(before)) =>
        (playing.copy(holding = playing.holding.moved(before)), Cmd.None)

      case (Model.Start(joining, _), Msg.Failed(reason)) =>
        (Model.Start(joining, Some(reason)), Cmd.None)

      case (seated: Model.Seated, Msg.Failed(reason)) =>
        (seated.copy(error = Some(reason)), Cmd.None)

      case (playing: Model.Playing, Msg.Failed(reason)) =>
        (playing.copy(error = Some(reason)), Cmd.None)

      // A message that does not belong to the state the model is in. A reply arriving after the
      // model moved on is one way to reach this, and `Hover` is another, so dropping it is right.
      case _ => (model, Cmd.None)
    }
  }

  /** A hand carries over from one view to the next while a round is in play, which is what keeps
    * the order a player chose. A new round deals new cards, and reconciling then keeps none of
    * them, so it needs no case of its own.
    */
  private def seat(credentials: Credentials, view: PlayerView, kept: Option[Holding]): Model = {
    view match {
      case playing: PlayerView.InPlay =>
        Model.Playing(credentials, playing, kept.fold(Holding.of(playing))(_.reconcile(playing)), None)
      case waiting: PlayerView.AwaitingOpponent => Model.Seated(credentials, waiting, None)
      case over: PlayerView.Over                => Model.Seated(credentials, over, None)
    }
  }
}
```

- [x] **Step 5: Make the screens and the subscription match the new model**

In `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`, replace `apply` and `seated` with:

```scala
  def apply(model: Model): Html[Msg] = {
    model match {
      case Model.Start(joining, error)      => start(joining, error)
      case Model.Connecting(_)              => div(cls := "waiting")(text("Taking your seat..."))
      case Model.Seated(_, view, error)     => seated(view, error)
      case Model.Playing(_, view, _, error) => table(view, error)
    }
  }
```

```scala
  private def seated(
      view: PlayerView.AwaitingOpponent | PlayerView.Over,
      error: Option[String]
  ): Html[Msg] = {
    view match {
      case waiting: PlayerView.AwaitingOpponent => awaiting(waiting, error)
      case over: PlayerView.Over                => finished(over)
    }
  }
```

In `modules/ui/src/main/scala/wiggly/gin/ui/Main.scala`, replace `subscriptions` with:

```scala
  def subscriptions(model: Model): Sub[IO, Msg] = {
    model match {
      case Model.Connecting(seat)       => Events.watch(seat)
      case Model.Seated(seat, _, _)     => Events.watch(seat)
      case Model.Playing(seat, _, _, _) => Events.watch(seat)
      case Model.Start(_, _)            => Sub.None
    }
  }
```

`Events.watch` names its subscription after the game id. The move from `Seated` to `Playing`
therefore keeps the stream that is open and does not open a second one.

- [x] **Step 6: Run the suites and make sure they pass**

Run: `sbt "ui/testOnly wiggly.gin.ui.UpdateSuite wiggly.gin.ui.ViewSuite"`

Expected: every test passes.

- [x] **Step 7: Run the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`

Expected: green.

- [x] **Step 8: Commit**

```bash
git add modules/ui/src
git commit -m "Seat a round in play at a table that keeps the player's hand"
```

---

### Task 3: Draw the cards as pictures

Every card on the table becomes an SVG face or back. Nothing is clickable yet beyond today's
buttons.

**Files:**
- Create: `modules/ui/src/main/scala/wiggly/gin/ui/Cards.scala`
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`
- Modify: `modules/server/src/main/resources/web/gin.css`
- Modify: `modules/server/src/main/resources/web/index.html`
- Modify: `build.sbt`
- Test: `modules/ui/src/test/scala/wiggly/gin/ui/CardsSuite.scala`
- Test: `modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala`

**Interfaces:**
- Consumes: `Model.Playing` and `Holding.of` from Tasks 1 and 2, `ViewGen.inPlay` and
  `ViewGen.held`.
- Produces:
  - `Cards.face[M](card: Card): Html[M]`, an `svg` with class `card-art face red|black`,
    `role="img"` and `aria-label` set to `Cards.name(card)`.
  - `Cards.back[M]: Html[M]`, an `svg` with class `card-art back` and
    `aria-label="a face-down card"`.
  - `Cards.name(card: Card): String`, for example `"nine of spades"`.
  - `Cards.short(card: Card): String`, for example `"9♠"`.
  - The CSS classes `fan`, `slot`, `pick`, `selected`, `end` and `meld-0` to `meld-2`, which Task 4
    and Task 5 use.

- [x] **Step 1: Write the failing tests**

Create `modules/ui/src/test/scala/wiggly/gin/ui/CardsSuite.scala`:

```scala
package wiggly.gin.ui

import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{Card, Rank, Suit}

object CardsSuite extends SimpleIOSuite {

  pureTest("a card is named the way a person says it") {
    expect.same("nine of spades", Cards.name(Card(Rank.Nine, Suit.Spades))) and
      expect.same("queen of hearts", Cards.name(Card(Rank.Queen, Suit.Hearts)))
  }

  pureTest("a card's short name is its corner index") {
    expect.same("9♠", Cards.short(Card(Rank.Nine, Suit.Spades))) and
      expect.same("10♦", Cards.short(Card(Rank.Ten, Suit.Diamonds))) and
      expect.same("A♣", Cards.short(Card(Rank.Ace, Suit.Clubs))) and
      expect.same("K♥", Cards.short(Card(Rank.King, Suit.Hearts)))
  }
}
```

In `modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala`, make the suite a `Checkers` suite,
render with Tyrian's `render` extension rather than `toString`, and add two properties. The
helper is called `drawn`, because a local `render` would hide that extension. The whole file
becomes:

```scala
package wiggly.gin.ui

import cats.Show
import tyrian.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.{GameId, Player, PlayerView, Token}
import wiggly.gin.core.port.Credentials
import wiggly.gin.gen.ViewGen
import wiggly.gin.gen.ViewGen.held

object ViewSuite extends SimpleIOSuite with Checkers {

  private given Show[PlayerView.InPlay] = Show.fromToString

  private val seat = Credentials(GameId("g1"), Player.One, Token("t1"))

  private val waiting = PlayerView.AwaitingOpponent(GameId("g1"), Player.One)

  pureTest("the start screen offers both ways in") {
    val rendered = drawn(Model.Start("", None))

    expect(rendered.contains("Create a game")) and expect(rendered.contains("Join"))
  }

  pureTest("the waiting screen shows the game to share") {
    expect(drawn(Model.Seated(seat, waiting, None)).contains("g1"))
  }

  pureTest("an error is put in front of the player") {
    val rendered = drawn(Model.Seated(seat, waiting, Some("that is not your turn")))

    expect(rendered.contains("that is not your turn"))
  }

  pureTest("a token is never drawn on any screen") {
    val rendered = drawn(Model.Seated(seat, waiting, None)) + drawn(Model.Connecting(seat))

    expect(!rendered.contains("t1"))
  }

  test("every card in the hand is drawn with its name") {
    forall(ViewGen.inPlay) { view =>
      val rendered = drawn(playing(view))

      expect(held(view).forall(card => rendered.contains(s"""aria-label="${Cards.name(card)}"""")))
    }
  }

  test("the other hand is drawn face down, one back for each card, beside the stock") {
    forall(ViewGen.inPlay) { view =>
      val backs = """aria-label="a face-down card"""".r.findAllIn(drawn(playing(view))).size

      expect.same(view.opponentSize + 1, backs)
    }
  }

  private def playing(view: PlayerView.InPlay): Model =
    Model.Playing(seat, view, Holding.of(view), None)

  private def drawn(model: Model): String = View(model).render
}
```

- [x] **Step 2: Run them and make sure they fail**

Run: `sbt "ui/testOnly wiggly.gin.ui.CardsSuite wiggly.gin.ui.ViewSuite"`

Expected: a compile failure, `Not found: Cards`.

- [x] **Step 3: Write `Cards`**

Create `modules/ui/src/main/scala/wiggly/gin/ui/Cards.scala`:

```scala
package wiggly.gin.ui

import tyrian.Html.*
import tyrian.{Aria, Html, SVG}
import wiggly.gin.core.domain.{Card, Rank, Suit}

/** Cards as pictures.
  *
  * A face carries its index in two opposite corners, as a printed card does, so that it reads the
  * same from either side of a fan. The middle is one large suit rather than a pip layout: the
  * index carries the meaning, and the rest is drawing code nobody needs to read.
  */
object Cards {

  def face[M](card: Card): Html[M] = {
    svg(
      SVG.viewBox    := "0 0 70 100",
      cls            := s"card-art face ${colour(card.suit)}",
      role           := "img",
      Aria.ariaLabel := name(card)
    )(
      outline,
      index(card),
      SVG.g(attribute("transform", "rotate(180 35 50)"))(index(card)),
      SVG.textTag(SVG.x := "35", SVG.y := "62", cls := "pip")(text(symbol(card.suit)))
    )
  }

  /** The pattern a back fills with is defined once, in the page, rather than once for every back
    * on the table, because an id has to be unique in a document.
    */
  def back[M]: Html[M] = {
    svg(
      SVG.viewBox    := "0 0 70 100",
      cls            := "card-art back",
      role           := "img",
      Aria.ariaLabel := "a face-down card"
    )(
      outline,
      SVG.rect(
        SVG.x := "6",
        SVG.y := "6",
        attribute("width", "58"),
        attribute("height", "88"),
        SVG.rx := "3",
        cls    := "weave"
      )
    )
  }

  def name(card: Card): String =
    s"${card.rank.toString.toLowerCase} of ${card.suit.toString.toLowerCase}"

  def short(card: Card): String = s"${rankIndex(card.rank)}${symbol(card.suit)}"

  private def outline[M]: Html[M] = {
    SVG.rect(
      SVG.x := "1",
      SVG.y := "1",
      attribute("width", "68"),
      attribute("height", "98"),
      SVG.rx := "6",
      cls    := "outline"
    )
  }

  private def index[M](card: Card): Html[M] = {
    SVG.g(cls := "index")(
      SVG.textTag(SVG.x := "9", SVG.y := "16")(text(rankIndex(card.rank))),
      SVG.textTag(SVG.x := "9", SVG.y := "28")(text(symbol(card.suit)))
    )
  }

  private def rankIndex(rank: Rank): String = {
    rank match {
      case Rank.Ace   => "A"
      case Rank.Jack  => "J"
      case Rank.Queen => "Q"
      case Rank.King  => "K"
      case other      => other.order.toString
    }
  }

  private def symbol(suit: Suit): String = {
    suit match {
      case Suit.Spades   => "♠"
      case Suit.Hearts   => "♥"
      case Suit.Diamonds => "♦"
      case Suit.Clubs    => "♣"
    }
  }

  private def colour(suit: Suit): String = {
    suit match {
      case Suit.Hearts | Suit.Diamonds => "red"
      case Suit.Clubs | Suit.Spades    => "black"
    }
  }
}
```

If `svg`, `role`, `SVG.textTag` or `Aria.ariaLabel` does not resolve, read the names in
`tyrian-tags_sjs1_3-0.14.0-sources.jar` (`HtmlTags.scala`, `SVGTags.scala`, `AriaAttributes.scala`).
This plan took them from that jar. Tyrian names the SVG `text` element
`textTag`, because `text` is the text node.

- [x] **Step 4: Draw the table with `Cards`**

In `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`, replace `opponent` and `pile` with:

```scala
  private def opponent(view: PlayerView.InPlay): Html[Msg] =
    div(cls := "opponent fan")(List.fill(view.opponentSize)(Cards.back[Msg]))

  private def pile(view: PlayerView.InPlay): Html[Msg] = {
    div(cls := "piles")(
      div(cls := "stock")(Cards.back, p(text(s"Stock ${view.stockSize}"))),
      div(cls := "upcard")(
        view.upcard.fold(p(cls := "empty-pile")(text("The pile is empty")))(Cards.face),
        p(text(s"Pile ${view.discardSize}"))
      )
    )
  }
```

Replace the body of `hand` so that each meld and the deadwood draw faces:

```scala
  private def hand(view: PlayerView.InPlay): Html[Msg] = {
    div(cls := "hand")(
      div(cls := "melds")(
        view.arrangement.melds.map(meld => div(cls := "fan")(meld.cards.toList.map(Cards.face[Msg])))
      ),
      div(cls := "fan")(view.arrangement.deadwood.map(Cards.face[Msg])),
      p(cls := "count")(text(s"Deadwood ${view.arrangement.deadwoodValue}"))
    )
  }
```

In `label`, use the short name:

```scala
      case Move.Discard(c)  => s"Discard ${Cards.short(c)}"
      case Move.Knock(c)    => s"Knock with ${Cards.short(c)}"
```

Delete the private `card` and `name` functions. Nothing calls them now.

- [x] **Step 5: Style the cards**

In `modules/server/src/main/resources/web/gin.css`, add three tints to `:root`:

```css
  --meld-0: #2f6f4f;
  --meld-1: #2a5d9f;
  --meld-2: #b5761a;
```

Delete the `.card`, `.card.red`, `.card.black`, `.meld` and `.deadwood` rules, and add:

```css
.card-art { display: block; width: 4.375rem; height: 6.25rem; }
.card-art .outline { fill: #fff; stroke: var(--line); stroke-width: 1; }
.card-art text { font: 600 12px system-ui, sans-serif; text-anchor: middle; }
.card-art .pip { font-size: 30px; }

/* The one piece of styling that carries meaning rather than decoration. */
.face.red text { fill: var(--red); }
.face.black text { fill: var(--ink); }

.back .weave { fill: url(#weave); }

/* Eleven cards do not fit the column side by side, so a hand overlaps rather than widens. */
.fan { display: flex; align-items: flex-end; margin: 0.4rem 0; }
.fan > * + * { margin-left: -1.5rem; }

.piles { display: flex; gap: 1.5rem; margin: 1rem 0; }
.piles p { margin: 0.2rem 0 0; font-size: 0.9rem; }
.empty-pile { width: 4.375rem; height: 6.25rem; border: 1px dashed var(--line); border-radius: 0.4rem; }

.slot { transition: transform 0.12s ease; border-radius: 0 0 0.4rem 0.4rem; }
.slot.selected { transform: translateY(-0.75rem); }
.slot.selected .outline { stroke: var(--ink); stroke-width: 2; }
.slot.meld-0 { border-bottom: 0.25rem solid var(--meld-0); }
.slot.meld-1 { border-bottom: 0.25rem solid var(--meld-1); }
.slot.meld-2 { border-bottom: 0.25rem solid var(--meld-2); }

.pick { display: block; padding: 0; border: 0; background: none; cursor: pointer; }
.pick:hover { transform: translateY(-0.25rem); }

.end { width: 2rem; min-height: 6.25rem; }
.sorting button { margin: 0 0.3rem 0.3rem 0; }
```

In `modules/server/src/main/resources/web/index.html`, add the pattern as the first child of
`body`:

```html
    <svg width="0" height="0" style="position: absolute" aria-hidden="true">
      <defs>
        <pattern id="weave" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
          <rect width="6" height="6" fill="#7a1f24" />
          <rect width="3" height="6" fill="#962a30" />
        </pattern>
      </defs>
    </svg>
```

- [x] **Step 6: Exclude `Cards` from coverage beside `View`**

In `build.sbt`, change the exclusion line to:

```scala
ThisBuild / coverageExcludedFiles := ".*/server/Main;.*/server/HttpServer;.*/ui/Main;.*/ui/View;.*/ui/Cards"
```

Coverage is off for every Scala.js target, so this changes no figure today. If that changes, the
list is already right.

- [x] **Step 7: Run the suites and make sure they pass**

Run: `sbt "ui/testOnly wiggly.gin.ui.CardsSuite wiggly.gin.ui.ViewSuite"`

Expected: every test passes.

- [ ] **Step 8: Look at it**

```bash
sbt server/run
```

Open `http://localhost:8080/play` in two browser windows, create a game in one and join it from the
other. If the sandbox does not publish the port, ask the user to run
`sbx ports <sandbox-name> --publish 8080:8080/tcp` on their host, where `<sandbox-name>` is the
value of `$SANDBOX_NAME`.

Make sure that the faces show their corners and their middle suit, that the backs show the weave,
and that red suits are red. If every card is an empty box, the browser created the SVG elements in
the HTML namespace. In that case, read how Tyrian's `Rendering.toVNode` passes `svg` to snabbdom,
and say so before working around it.

- [x] **Step 9: Run the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`

Expected: green.

- [x] **Step 10: Commit**

```bash
git add build.sbt modules/ui/src modules/server/src/main/resources/web
git commit -m "Draw the cards as pictures"
```

---

### Task 4: Play with the mouse

The stock and the upcard draw. A card in the hand is selected and then discarded or knocked with. The
hand is drawn in the holding's order, with a tint for each meld and the three sort buttons.

**Files:**
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`
- Create: `modules/ui/src/test/scala/wiggly/gin/ui/Handlers.scala`
- Test: `modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala`

**Interfaces:**
- Consumes: `Holding`, `Sorting`, the `Msg` cases from Task 2, `Cards` and the CSS classes from
  Task 3, `ViewGen.discarding`.
- Produces:
  - `Handlers.sent(html: Html[Msg], name: String): List[Msg]`, the messages the handlers for one
    event name send, in document order.
  - `Handlers.count(html: Html[Msg], name: String): Int`
  - In `View`: `slot(card: Card, tint: Option[String], selectable: Boolean, selected: Boolean): Html[Msg]`,
    which Task 5 changes. `hand(view, holding, available)` draws one `div.fan` of slots.

- [x] **Step 1: Write the handler reader**

Create `modules/ui/src/test/scala/wiggly/gin/ui/Handlers.scala`:

```scala
package wiggly.gin.ui

import org.scalajs.dom
import tyrian.{Event, Html, Tag}

/** What a rendered page would send, read without a browser.
  *
  * A handler that ignores its event can be run here with a bare one. `dragstart` reads the event's
  * transfer, which Node does not have, so that handler is counted rather than run.
  */
object Handlers {

  def sent(html: Html[Msg], name: String): List[Msg] =
    named(html, name).map(_.msg.asInstanceOf[dom.Event => Msg](new dom.Event(name)))

  def count(html: Html[Msg], name: String): Int = named(html, name).size

  private def named(html: Html[?], name: String): List[Event[?, ?]] = {
    html match {
      case Tag(_, attributes, children, _) =>
        attributes.collect { case event: Event[?, ?] if event.name == name => event } ++
          children.flatMap {
            case child: Html[?] => named(child, name)
            case _              => Nil
          }
      case _ => Nil
    }
  }
}
```

- [x] **Step 2: Write the failing view tests**

In `modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala`, change the domain import to
`wiggly.gin.core.domain.*`, and add these values and tests:

```scala
  private val views: Gen[PlayerView.InPlay] = Gen.oneOf(ViewGen.inPlay, ViewGen.discarding)

  private def clicks(model: Model): List[Msg] = Handlers.sent(View(model), "click")

  private def chosen(view: PlayerView.InPlay): Option[Holding] = {
    Moves.available(view).collectFirst { case Move.Discard(card) => card }.map { card =>
      Holding.of(view).toggled(card, view)
    }
  }

  test("a pile can be clicked exactly when drawing from it is a move") {
    forall(views) { view =>
      val sent      = clicks(playing(view))
      val available = Moves.available(view)

      expect.same(available.contains(Move.DrawStock), sent.contains(Msg.Play(Move.DrawStock))) and
        expect.same(available.contains(Move.DrawDiscard), sent.contains(Msg.Play(Move.DrawDiscard)))
    }
  }

  test("a card in the hand can be clicked exactly when it is a legal discard") {
    forall(views) { view =>
      val toggles = clicks(playing(view)).collect { case Msg.Toggle(card) => card }.toSet
      val legal   = Moves.available(view).collect { case Move.Discard(card) => card }.toSet

      expect.same(legal, toggles)
    }
  }

  test("with a card selected, the buttons are its discard and, when it is legal, its knock") {
    forall(ViewGen.discarding) { view =>
      chosen(view) match {
        case None          => success
        case Some(holding) => {
          val card     = holding.selected.toList
          val acting   = clicks(Model.Playing(seat, view, holding, None)).collect {
            case Msg.Play(move @ (Move.Discard(_) | Move.Knock(_))) => move
          }
          val expected = card.flatMap(c => List(Move.Discard(c), Move.Knock(c)))
            .filter(Moves.available(view).contains)

          expect.same(expected, acting)
        }
      }
    }
  }

  test("with no card selected, a discard waits for one") {
    forall(ViewGen.discarding) { view =>
      val model  = playing(view)
      val acting = clicks(model).collect {
        case Msg.Play(move @ (Move.Discard(_) | Move.Knock(_))) => move
      }

      expect(drawn(model).contains("Select a card to discard")) and expect(acting.isEmpty)
    }
  }

  test("each meld has its own tint, and deadwood has none") {
    forall(ViewGen.inPlay) { view =>
      val rendered = drawn(playing(view))
      val tinted   = (0 until 3).toList.map(i => s"meld-$i".r.findAllIn(rendered).size)
      val melds    = view.arrangement.melds.map(_.cards.size)

      expect.same(melds ++ List.fill(3 - melds.size)(0), tinted)
    }
  }

  test("the sort buttons are always there") {
    forall(ViewGen.inPlay) { view =>
      val sorts = clicks(playing(view)).collect { case Msg.Sort(by) => by }

      expect.same(Sorting.values.toList, sorts)
    }
  }
```

Add `import org.scalacheck.Gen` to the imports.

- [x] **Step 3: Run it and make sure it fails**

Run: `sbt "ui/testOnly wiggly.gin.ui.ViewSuite"`

Expected: the new tests fail. Nothing is clickable but the move buttons, there are no tints and
there are no sort buttons.

- [x] **Step 4: Write the table**

In `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`, change the `Playing` case of `apply` to
pass the holding:

```scala
      case Model.Playing(_, view, holding, error) => table(view, holding, error)
```

Replace `table`, `pile`, `hand` and `moves` with the functions below, and delete the old ones.
`scoreboard`, `opponent`, `lastRound`, `finished`, `label`, `who`, `problem` and `empty` stay.

```scala
  private def table(view: PlayerView.InPlay, holding: Holding, error: Option[String]): Html[Msg] = {
    val available = Moves.available(view)

    div(cls := "table")(
      scoreboard(view),
      opponent(view),
      piles(view, available),
      hand(view, holding, available),
      moves(holding, available),
      view.previous.fold(empty)(lastRound),
      problem(error)
    )
  }

  private def piles(view: PlayerView.InPlay, available: List[Move]): Html[Msg] = {
    val upcard = view.upcard.fold(p(cls := "empty-pile")(text("The pile is empty"))) { up =>
      pick(available.contains(Move.DrawDiscard), Msg.Play(Move.DrawDiscard))(Cards.face(up))
    }

    div(cls := "piles")(
      div(cls := "stock")(
        pick(available.contains(Move.DrawStock), Msg.Play(Move.DrawStock))(Cards.back),
        p(text(s"Stock ${view.stockSize}"))
      ),
      div(cls := "upcard")(upcard, p(text(s"Pile ${view.discardSize}")))
    )
  }

  /** The order is the player's. Which meld a card is in is the server's, so it is drawn as a tint
    * that travels with the card rather than as a position the player could break up.
    */
  private def hand(view: PlayerView.InPlay, holding: Holding, available: List[Move]): Html[Msg] = {
    val tints = view.arrangement.melds.zipWithIndex.flatMap { case (meld, i) =>
      meld.cards.toList.map(_ -> s"meld-$i")
    }.toMap

    div(cls := "hand")(
      div(cls := "fan")(holding.order.map { card =>
        slot(card, tints.get(card), available.contains(Move.Discard(card)), holding.selected.contains(card))
      }),
      div(cls := "sorting")(
        Sorting.values.toList.map(by => button(onClick(Msg.Sort(by)))(text(sortLabel(by))))
      ),
      p(cls := "count")(text(s"Deadwood ${view.arrangement.deadwoodValue}"))
    )
  }

  private def slot(card: Card, tint: Option[String], selectable: Boolean, selected: Boolean): Html[Msg] = {
    val classes = "slot" :: tint.toList ++ Option.when(selected)("selected").toList

    div(cls := classes.mkString(" "))(pick(selectable, Msg.Toggle(card))(Cards.face(card)))
  }

  /** A discard names the card it acts on, so in a discard phase the buttons wait for one. */
  private def moves(holding: Holding, available: List[Move]): Html[Msg] = {
    val discarding = available.exists {
      case Move.Discard(_) => true
      case _               => false
    }

    val buttons =
      if (discarding) {
        holding.selected.fold(List(button(disabled(true))(text("Select a card to discard")))) { card =>
          List(Move.Discard(card), Move.Knock(card)).filter(available.contains).map(play)
        }
      } else {
        available.filter(_ === Move.Pass).map(play)
      }

    div(cls := "moves")(buttons)
  }

  private def play(move: Move): Html[Msg] = button(onClick(Msg.Play(move)))(text(label(move)))

  /** Something a player can click is a button, so that the keyboard reaches it as well. */
  private def pick(enabled: Boolean, msg: Msg)(art: Html[Msg]): Html[Msg] =
    if (enabled) button(cls := "pick", onClick(msg))(art) else art

  private def sortLabel(by: Sorting): String = {
    by match {
      case Sorting.ByMelds => "By melds"
      case Sorting.BySuit  => "By suit"
      case Sorting.ByRank  => "By rank"
    }
  }
```

The `.melds` rule left `gin.css` in Task 3, and no class in the view refers to it now.

- [x] **Step 5: Run the view suite and make sure it passes**

Run: `sbt "ui/testOnly wiggly.gin.ui.ViewSuite"`

Expected: every test passes. If "each meld has its own tint" fails with too many matches, some
other text on the page contains `meld-`. Find it in the rendered string and rename it.

- [ ] **Step 6: Play a round with the mouse**

Run `sbt server/run` and open two windows as in Task 3. Make sure that each of these holds:

| Check | Why it is here |
| --- | --- |
| A click on the stock or the upcard draws, and only on your turn | The piles follow `Moves.available`. |
| After a draw, a click on a card lifts it and outlines it | `Toggle` reaches the holding. |
| A second click on it puts it down | `toggled` is its own inverse. |
| The card you took from the pile cannot be selected | The rule is `core`'s, not the view's. |
| "Knock with" appears only with a low enough hand | The knock button follows `Moves.available`. |
| The three sort buttons reorder the hand, and the tints stay with their cards | The order is the player's, the melds are the server's. |
| After your discard, your hand keeps the order you chose | `reconcile` keeps the order across views. |

- [x] **Step 7: Run the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`

Expected: green.

- [x] **Step 8: Commit**

```bash
git add modules/ui/src
git commit -m "Play by clicking the piles and the cards in hand"
```

---

### Task 5: Drag cards into order

Each card in the hand can be lifted and dropped before another card, or at the end of the row.

**Files:**
- Modify: `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`
- Test: `modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala`

**Interfaces:**
- Consumes: `Msg.Lift`, `Msg.Drop` and `Msg.Hover` from Task 2. `slot` and `hand` from Task 4.
  `Handlers` from Task 4.
- Produces: nothing a later task uses.

- [x] **Step 1: Write the failing view tests**

Add to `modules/ui/src/test/scala/wiggly/gin/ui/ViewSuite.scala`:

```scala
  test("every card in the hand is a drop target, and so is the end of the row") {
    forall(ViewGen.inPlay) { view =>
      val drops = Handlers.sent(View(playing(view)), "drop")

      expect.same(held(view).map(card => Msg.Drop(Some(card))) :+ Msg.Drop(None), drops)
    }
  }

  test("every drop target cancels dragover, or the browser would refuse the drop") {
    forall(ViewGen.inPlay) { view =>
      val html = View(playing(view))

      expect.same(Handlers.count(html, "drop"), Handlers.count(html, "dragover"))
    }
  }

  test("every card in the hand can be lifted") {
    forall(ViewGen.inPlay) { view =>
      val html = View(playing(view))

      expect.same(held(view).size, Handlers.count(html, "dragstart")) and
        expect.same(held(view).size, """draggable="true"""".r.findAllIn(html.render).size)
    }
  }
```

- [x] **Step 2: Run it and make sure it fails**

Run: `sbt "ui/testOnly wiggly.gin.ui.ViewSuite"`

Expected: the three new tests fail with no drop, dragover or dragstart handlers.

- [x] **Step 3: Make the slots draggable**

In `modules/ui/src/main/scala/wiggly/gin/ui/View.scala`, add the imports:

```scala
import org.scalajs.dom
import tyrian.Attr
```

Replace `slot` with:

```scala
  private def slot(card: Card, tint: Option[String], selectable: Boolean, selected: Boolean): Html[Msg] = {
    val classes = "slot" :: tint.toList ++ Option.when(selected)("selected").toList

    div(
      cls := classes.mkString(" "),
      attribute("draggable", "true"),
      lift(card),
      onDragOver(Msg.Hover),
      onDrop(Msg.Drop(Some(card)))
    )(pick(selectable, Msg.Toggle(card))(Cards.face(card)))
  }

  /** Tyrian cancels the default of every event unless told not to, and cancelling `dragstart`
    * cancels the drag. Firefox also starts a drag only once something is on the transfer.
    */
  private def lift(card: Card): Attr[Msg] = {
    tyrian
      .Event(
        "dragstart",
        (event: dom.DragEvent) => {
          event.dataTransfer.setData("text/plain", Cards.name(card))
          Msg.Lift(card)
        }
      )
      .noPreventDefault
  }
```

In `hand`, add the end of the row as the last child of the fan:

```scala
      div(cls := "fan")(
        holding.order.map { card =>
          slot(card, tints.get(card), available.contains(Move.Discard(card)), holding.selected.contains(card))
        } :+ div(cls := "end", onDragOver(Msg.Hover), onDrop(Msg.Drop(None)))()
      ),
```

`onDragOver` and `onDrop` keep Tyrian's default, which calls `preventDefault`. That is what makes
an element a drop target, and it stops Firefox from opening the dropped text as a page.

- [x] **Step 4: Run the view suite and make sure it passes**

Run: `sbt "ui/testOnly wiggly.gin.ui.ViewSuite"`

Expected: every test passes.

- [ ] **Step 5: Drag in real browsers**

No test reaches this, because Node has no drag and drop. Run `sbt server/run` and open the table in
Firefox and in Chrome. In each, make sure that each of these holds:

| Check | Why it is here |
| --- | --- |
| A card dragged onto another lands directly before it | `dragstart`, `dragover` and `drop` all fire. |
| A card dragged past the last card lands last | The end of the row is a drop target. |
| A drag works on the other player's turn too | The order is the player's own in every phase. |
| A drag starts from a selectable card, in a discard phase | A `button` inside a draggable element can stop the drag in Firefox. |
| A drop does not open a new page | `drop` cancels its default. |
| A move by the other player during a drag does not lose the card | `reconcile` keeps a lifted card. |

If a drag does not start from a selectable card in Firefox, the nested `button` is the cause. In
that case, put `role="button"`, `tabindex="0"` and the `onClick` on the slot itself. Then remove the
inner `button` from hand cards. If dragging does not work at all in one
of the browsers, stop and report it. The spec says that dragging then moves out of this work and
the sort buttons stay, and that decision belongs to the user.

- [x] **Step 6: Run the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`

Expected: green.

- [x] **Step 7: Commit**

```bash
git add modules/ui/src
git commit -m "Drag cards into the order a player wants"
```

---

### Task 6: Play a match through the container, and write it down

**Files:**
- Modify: `docs/SPEC-WEB-UI.md`
- Modify: `docs/SPEC-CARD-TABLE.md`
- Modify: `docs/PLAN-CARD-TABLE.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Build the image and play a whole match in it**

```bash
sbt server/Docker/publishLocal
docker run --rm -p 8080:8080 wiggly-gin:latest
```

Open `http://localhost:8080/play` in two browser windows. Play a whole match to 100 points with the
mouse only, apart from Pass. Make sure that the checks in Task 4 Step 6 and Task 5 Step 5 hold in
the image. Make sure that a reload keeps the seat. A reload starts the hand from the server's
arrangement, as the spec says.

- [x] **Step 2: Update the earlier spec**

In `docs/SPEC-WEB-UI.md`, replace the sentence "Cards are drawn in CSS as text, with the suit in
its own color. No graphics and no animation." with:

```markdown
Cards were drawn in CSS as text when this spec landed. [The card table spec](SPEC-CARD-TABLE.md)
replaced that with pictures and mouse play.
```

In its "Deliberately left out" list, replace the line about animation, card graphics and drag and
drop with:

```markdown
- No animation. Card graphics and drag and drop came later, in
  [the card table spec](SPEC-CARD-TABLE.md).
```

- [x] **Step 3: Record where the code came out different**

If a task above differs from `docs/SPEC-CARD-TABLE.md`, add a section "Where the code came out
different" to that spec. Use the three columns of the same section in `docs/SPEC-WEB-UI.md`: the
spec said, the code does, and why. If nothing differed, add no section.

At the top of this plan, under **Spec**, add a **Status** line that says the plan is implemented,
and name any step that nobody did.

- [x] **Step 4: Run the gate**

Run: `SBT_TPOLECAT_CI=1 sbt scalafmtCheckAll scalafmtSbtCheck test`

Expected: green.

- [x] **Step 5: Commit**

```bash
git add docs
git commit -m "Write down the card table"
```

---

## What this plan does not do

The spec leaves five things out on purpose, and this plan keeps them out:

- pip layouts and court art
- animation past the lift
- pointer-event dragging
- a saved hand order
- layout work past the single column

Two things are worth knowing while the work is in progress.

Tyrian's names in this plan were read from the 0.14.0 source jar, not from a compiler. The shapes
are right even where a name is not. Task 3 Step 3 says where to read the real ones.

The tests read the view as a value, with no browser. That proves which messages a page sends. It
does not prove that a browser draws the SVG or runs a drag. Task 3 Step 8, Task 5 Step 5 and Task 6
Step 1 are the checks that only a person at a browser can do.
