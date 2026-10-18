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
    case Enter(slot: Int)
    case Drop
    case Release
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
      Gen.choose(0, held(view).size).map(Op.Enter(_)),
      Gen.const(Op.Drop),
      Gen.const(Op.Release)
    )

    Gen.choose(0, 12).flatMap(n => Gen.listOfN(n, op)).map(view -> _)
  }

  private val choices: Gen[(PlayerView.InPlay, Card)] =
    views.flatMap(view => Gen.oneOf(held(view)).map(view -> _))

  private val drags: Gen[(PlayerView.InPlay, Card, Int)] = for {
    view <- ViewGen.inPlay
    card <- Gen.oneOf(held(view))
    slot <- Gen.choose(0, held(view).size)
  } yield (view, card, slot)

  private def played(view: PlayerView.InPlay, run: List[Op]): Holding = {
    run.foldLeft(Holding.of(view)) { (holding, op) =>
      op match {
        case Op.Toggle(card) => holding.toggled(card, view)
        case Op.Sort(by)     => holding.sorted(by, view)
        case Op.Lift(card)   => holding.lifted(card)
        case Op.Enter(slot)  => holding.hovered(slot)
        case Op.Drop         => holding.dropped
        case Op.Release      => holding.released
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

  test("whatever the player does, the hand on show holds exactly the cards the server dealt") {
    forall(runs) { case (view, run) =>
      expect.same(held(view).sorted, played(view, run).shown.sorted)
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

  test("lifting a card leaves it where it was until it moves over another slot") {
    forall(choices) { case (view, card) =>
      expect.same(held(view), Holding.of(view).lifted(card).shown)
    }
  }

  test("a lifted card over a slot is shown in that slot, whichever way it moved") {
    forall(drags) { case (view, card, slot) =>
      val shown = Holding.of(view).lifted(card).hovered(slot).shown

      expect
        .same(slot.min(held(view).size - 1), shown.indexOf(card))
        .and(expect.same(held(view).filterNot(_ === card), shown.filterNot(_ === card)))
    }
  }

  test("a drop keeps the hand as it was shown and lets go of the card") {
    forall(drags) { case (view, card, slot) =>
      val over    = Holding.of(view).lifted(card).hovered(slot)
      val dropped = over.dropped

      expect.same(over.shown, dropped.order).and(expect(dropped.dragging.isEmpty))
    }
  }

  test("a drag that ends without a drop puts the hand back as it was") {
    forall(drags) { case (view, card, slot) =>
      expect.same(Holding.of(view), Holding.of(view).lifted(card).hovered(slot).released)
    }
  }

  test("a drag that ends after a drop keeps the drop") {
    forall(drags) { case (view, card, slot) =>
      val dropped = Holding.of(view).lifted(card).hovered(slot).dropped

      expect.same(dropped, dropped.released)
    }
  }

  test("moving over a slot or dropping with nothing lifted changes nothing") {
    forall(drags) { case (view, _, slot) =>
      val start = Holding.of(view)

      expect.same(start, start.hovered(slot)).and(expect.same(start, start.dropped))
    }
  }

  test("sorting by melds gives back the server's order") {
    forall(runs) { case (view, run) =>
      expect.same(held(view), played(view, run).sorted(Sorting.ByMelds, view).order)
    }
  }

  test("sorting by suit groups each suit, high to low") {
    forall(runs) { case (view, run) =>
      val keys = played(view, run).sorted(Sorting.BySuit, view).order.map(suitThenRank)

      expect.same(keys.sortBy { case (suit, rank) => (suit, -rank) }, keys)
    }
  }

  test("sorting by rank puts the ranks high to low, suits breaking ties") {
    forall(runs) { case (view, run) =>
      val keys = played(view, run).sorted(Sorting.ByRank, view).order.map(rankThenSuit)

      expect.same(keys.sortBy { case (rank, suit) => (-rank, suit) }, keys)
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
