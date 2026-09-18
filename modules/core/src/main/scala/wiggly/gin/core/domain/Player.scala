package wiggly.gin.core.domain

import cats.{Eq, Show}

/** One of the two people playing a match.
  *
  * Numbered rather than named after a role, because a role lasts one round and a person lasts the
  * match: the deal passes at the end of every round that scores, so a seat cannot key a score.
  * Which person is which number is a question for the identity the HTTP adapter carries, not for
  * the rules.
  */
enum Player {
  case One, Two

  def other: Player = {
    this match {
      case One => Two
      case Two => One
    }
  }
}

object Player {
  given Eq[Player] = Eq.fromUniversalEquals

  given Show[Player] = Show.fromToString
}

/** Who dealt the round, and so who did not.
  *
  * Naming the dealer names the non-dealer too, which is why there is one field rather than two: a
  * seating that sits one person in both chairs is not a value anybody can write.
  */
final case class Seats(dealer: Player) {
  def nonDealer: Player = dealer.other

  /** The seating for the next round. The deal passes after every round that scores, and a dead
    * round is dealt again by the same dealer, so the caller decides when to pass it.
    */
  def passed: Seats = Seats(dealer.other)
}

object Seats {
  given Eq[Seats] = Eq.fromUniversalEquals

  given Show[Seats] = Show.fromToString
}
