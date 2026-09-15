package wiggly.gin.core

import cats.{Order, Show}

/** A rank carries two numbers, and the distinction between them matters.
  *
  * `order` is the position in a run: the ace is low, and runs do not wrap around, so `K-A-2` is not
  * a run. `deadwoodValue` is what the card counts for when it is left unmelded: an ace is one, a
  * court card is ten, and everything else is its pip value.
  */
enum Rank(val order: Int, val deadwoodValue: Int) {
  case Ace   extends Rank(1, 1)
  case Two   extends Rank(2, 2)
  case Three extends Rank(3, 3)
  case Four  extends Rank(4, 4)
  case Five  extends Rank(5, 5)
  case Six   extends Rank(6, 6)
  case Seven extends Rank(7, 7)
  case Eight extends Rank(8, 8)
  case Nine  extends Rank(9, 9)
  case Ten   extends Rank(10, 10)
  case Jack  extends Rank(11, 10)
  case Queen extends Rank(12, 10)
  case King  extends Rank(13, 10)

  /** The rank one step up a run, or `None` at the king. Runs stop at the king rather than wrapping
    * back to the ace, so this is the thing run enumeration walks rather than doing arithmetic on
    * [[order]] and hoping the bounds were checked.
    */
  def next: Option[Rank] = Option.when(this != Rank.King)(Rank.fromOrdinal(ordinal + 1))
}

object Rank {
  given Order[Rank] = Order.by(_.order)

  given Show[Rank] = Show.fromToString
}
