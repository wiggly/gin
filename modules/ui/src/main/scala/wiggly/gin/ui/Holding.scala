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
      case None                                => this
      case Some(card) if before.contains(card) => copy(dragging = None)
      case Some(card)                          => {
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
