package wiggly.gin.core.domain

import cats.{Eq, Show}

/** One of the two seats at the table.
  *
  * The seats are named after who dealt rather than numbered, because that is the only distinction a
  * single round makes: the non-dealer is offered the upcard first and takes the first turn. It also
  * means the game state has no dealer field to keep consistent with anything. Which person sits in
  * which seat is a question for the identity the HTTP adapter carries, not for the rules.
  */
enum Player {
  case Dealer, NonDealer

  def other: Player = {
    this match {
      case Dealer    => NonDealer
      case NonDealer => Dealer
    }
  }
}

object Player {
  given Eq[Player] = Eq.fromUniversalEquals

  given Show[Player] = Show.fromToString
}
