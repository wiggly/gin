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

    Gen.choose(0, 12).flatMap(n => Gen.listOfN(n, op)).map(view -> _)
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
        case Op.Toggle(card) => holding.toggled(card, view)
        case Op.Sort(by)     => holding.sorted(by, view)
        case Op.Lift(card)   => holding.lifted(card)
        case Op.Drop(before) => holding.moved(before)
      }
    }
  }

  private def suitThenRank(card: Card): (Int, Int) = (card.suit.ordinal, card.rank.order)

  private def rankThenSuit(card: Card): (Int, Int) = (card.rank.order, card.suit.ordinal)

  private def legal(view: PlayerView.InPlay)(card: Card): Boolean =
    Moves.available(view).contains(Move.Discard(card))

  private def chosen(view: PlayerView.InPlay): Holding = {
    val first = Moves.available(view).collectFirst { case Move.Discard(card) => card }

    first.fold(Holding.of(view))(Holding.of(view).toggled(_, view))
  }

  test("a hand starts in the server's order with nothing chosen") {
    forall(ViewGen.inPlay) { view =>
      val holding = Holding.of(view)

      expect
        .same(held(view), holding.order)
        .and(expect(holding.selected.isEmpty))
        .and(expect(holding.dragging.isEmpty))
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

      expect
        .same(kept, next.order.take(kept.size))
        .and(expect(next.order.drop(kept.size).forall(card => !mine.order.contains(card))))
    }
  }

  test("a new view holds exactly the cards it deals") {
    forall(ViewGen.turn) { case (before, after) =>
      val next = Holding.of(before).sorted(Sorting.BySuit, before).reconcile(after)

      expect.same(held(after).sorted, next.order.sorted)
    }
  }

  test("a new view drops a selection that is no longer legal") {
    forall(ViewGen.discardTurn) { case (before, after) =>
      val holding = chosen(before)

      expect(holding.selected.isDefined)
        .and(expect(holding.reconcile(after).selected.forall(legal(after))))
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

      expect.same(Some(card), moved.order.lastOption).and(expect(moved.dragging.isEmpty))
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
      val keys = played(view, run).sorted(Sorting.BySuit, view).order.map(suitThenRank)

      expect.same(keys.sorted, keys)
    }
  }

  test("sorting by rank puts the ranks in order, suits breaking ties") {
    forall(runs) { case (view, run) =>
      val keys = played(view, run).sorted(Sorting.ByRank, view).order.map(rankThenSuit)

      expect.same(keys.sorted, keys)
    }
  }

  test("a discarding view always offers a discard") {
    forall(ViewGen.discarding) { view =>
      expect(Moves.available(view).exists {
        case _: Move.Discard => true
        case _               => false
      })
    }
  }
}
