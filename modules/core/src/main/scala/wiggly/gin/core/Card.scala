package wiggly.gin.core

import cats.syntax.show.*
import cats.{Order, Show}

final case class Card(rank: Rank, suit: Suit) {
  def deadwoodValue: Int = rank.deadwoodValue
}

object Card {

  /** Lexicographic on suit then rank, which is the order [[Deck.ordered]] is built in. Like
    * [[Suit]]'s order this is about having one canonical arrangement rather than about the rules.
    */
  given order: Order[Card] = Order.whenEqual(Order.by(_.suit), Order.by(_.rank))

  /** Sorting a plain collection of cards happens often enough to be worth having without an
    * import. It agrees with [[order]] by construction rather than by coincidence.
    */
  given Ordering[Card] = order.toOrdering

  given Show[Card] = Show.show(card => show"${card.rank} of ${card.suit}")
}
