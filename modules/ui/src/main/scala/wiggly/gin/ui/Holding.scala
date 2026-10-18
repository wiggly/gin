package wiggly.gin.ui

import cats.syntax.all.*
import wiggly.gin.core.domain.{Arrangement, Card, Move, Moves, PlayerView}

enum Sorting {
  case ByMelds, BySuit, ByRank
}

/** A card in the air, and the card it would land in front of if it were dropped now. `None` is the
  * end of the row.
  */
final case class Drag(card: Card, before: Option[Card])

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
    drag: Option[Drag]
) {

  def dragging: Option[Card] = drag.map(_.card)

  /** The order the hand would hold if the lifted card were dropped now, which is the order to draw
    * while it is in the air.
    */
  def shown: List[Card] = {
    drag.fold(order) { case Drag(card, before) =>
      val rest         = order.filterNot(_ === card)
      val (head, tail) = rest.splitAt(before.map(rest.indexOf).getOrElse(rest.size))

      head ++ (card :: tail)
    }
  }

  def reconcile(view: PlayerView.InPlay): Holding = {
    val held  = Holding.cardsIn(view.arrangement)
    val kept  = order.filter(held.contains)
    val added = held.filterNot(order.contains)

    new Holding(
      kept ++ added,
      selected.filter(Holding.discardable(view)),
      drag.filter(lifted => held.contains(lifted.card)).map { lifted =>
        lifted.copy(before = lifted.before.filter(held.contains))
      }
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

  def lifted(card: Card): Holding = {
    if (order.contains(card)) {
      copy(drag = Some(Drag(card, order.dropWhile(_ =!= card).drop(1).headOption)))
    } else {
      this
    }
  }

  /** Puts the lifted card in the slot under the pointer, counted along the hand as it is drawn.
    *
    * A slot rather than the card in it, because the cards move as the pointer does. The same slot
    * gives the same answer however many times the browser reports it, so a report from a slot
    * that has not been redrawn yet cannot move the card back.
    */
  def hovered(slot: Int): Holding = {
    copy(drag = drag.map { lifted =>
      lifted.copy(before = order.filterNot(_ === lifted.card).lift(slot))
    })
  }

  def dropped: Holding = copy(order = shown, drag = None)

  def released: Holding = copy(drag = None)
}

object Holding {

  def of(view: PlayerView.InPlay): Holding = new Holding(cardsIn(view.arrangement), None, None)

  private def cardsIn(arrangement: Arrangement): List[Card] =
    arrangement.melds.flatMap(_.cards.toList) ++ arrangement.deadwood

  private def discardable(view: PlayerView.InPlay)(card: Card): Boolean =
    Moves.available(view).contains(Move.Discard(card))
}
