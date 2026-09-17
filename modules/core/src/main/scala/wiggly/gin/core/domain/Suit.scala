package wiggly.gin.core.domain

import cats.{Order, Show}

enum Suit {
  case Spades, Hearts, Diamonds, Clubs
}

object Suit {

  /** Suits do not rank against one another in gin rummy. This order exists only so that a
    * collection of cards has a single canonical arrangement, which keeps melds comparable and test
    * expectations stable; nothing in the rules should ever ask which suit is higher.
    */
  given Order[Suit] = Order.by(_.ordinal)

  given Show[Suit] = Show.fromToString
}
