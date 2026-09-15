package wiggly.gin.core

import cats.data.NonEmptyList
import cats.syntax.all.*
import cats.{Order, Show}

/** Cards that count for nothing against the player holding them: either a set of the same rank or a
  * run in sequence within one suit.
  *
  * A meld can only be built through [[Meld.Set.from]] or [[Meld.Run.from]], so an invalid one cannot
  * be constructed and nothing downstream has to re-check the rules on a meld it has been handed.
  */
sealed trait Meld {

  /** The meld's cards in canonical order: ascending suit for a set, ascending rank for a run. */
  def cards: NonEmptyList[Card]

  def deadwoodValue: Int = cards.toList.map(_.deadwoodValue).sum
}

object Meld {

  /** The fewest cards that make a meld, for either kind. */
  val MinimumSize: Int = 3

  /** Three or four cards of one rank. Four is the ceiling without needing to be stated: the suits
    * have to differ, and there are only four of them.
    */
  final case class Set private (cards: NonEmptyList[Card]) extends Meld {
    def rank: Rank = cards.head.rank
  }

  object Set {
    def from(cards: List[Card]): Option[Set] =
      NonEmptyList.fromList(cards).map(_.sorted).filter(isSet).map(new Set(_))

    private def isSet(cards: NonEmptyList[Card]): Boolean = {
      cards.length >= MinimumSize &&
      cards.forall(_.rank === cards.head.rank) &&
      cards.map(_.suit).distinct.length === cards.length
    }
  }

  /** Three or more cards of one suit at consecutive ranks. */
  final case class Run private (cards: NonEmptyList[Card]) extends Meld {
    def suit: Suit    = cards.head.suit
    def lowest: Rank  = cards.head.rank
    def highest: Rank = cards.last.rank
  }

  object Run {
    def from(cards: List[Card]): Option[Run] =
      NonEmptyList.fromList(cards).map(_.sorted).filter(isRun).map(new Run(_))

    private def isRun(cards: NonEmptyList[Card]): Boolean = {
      cards.length >= MinimumSize &&
      cards.forall(_.suit === cards.head.suit) &&
      consecutive(cards.map(_.rank))
    }

    /** Consecutive with no wrap: a run stops at the king rather than turning the corner into the
      * ace. Walking [[Rank.next]] makes that structural instead of a bound to remember to check.
      */
    private def consecutive(ranks: NonEmptyList[Rank]): Boolean =
      ranks.toList.sliding(2).forall {
        case List(lower, higher) => lower.next.contains(higher)
        case _                   => true
      }
  }

  /** The meld these cards make, if they make one at all. A given hand of cards can be at most one
    * kind: three cards of a rank are never also in sequence.
    */
  def from(cards: List[Card]): Option[Meld] = Set.from(cards).orElse(Run.from(cards))

  /** Melds compare by their cards, which gives the arrangement search a stable order to enumerate
    * candidates in and so makes its choice between equally good arrangements reproducible.
    */
  given order: Order[Meld] = Order.by(_.cards)

  given Ordering[Meld] = order.toOrdering

  given Show[Meld] = Show.show { meld =>
    val cards = meld.cards.toList.map(_.show).mkString(", ")

    meld match {
      case _: Set => s"Set($cards)"
      case _: Run => s"Run($cards)"
    }
  }
}
