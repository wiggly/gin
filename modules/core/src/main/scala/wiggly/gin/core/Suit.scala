package wiggly.gin.core

import cats.{Order, Show}

enum Suit {
  case Clubs, Diamonds, Hearts, Spades
}

object Suit {

  /** Suits do not rank against one another in gin rummy. This order exists only so that a
    * collection of cards has a single canonical arrangement, which keeps melds comparable and test
    * expectations stable; nothing in the rules should ever ask which suit is higher.
    */
  given Order[Suit] = Order.by(_.ordinal)

  given Show[Suit] = Show.fromToString
}
