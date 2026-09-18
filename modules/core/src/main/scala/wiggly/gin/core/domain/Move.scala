package wiggly.gin.core.domain

import cats.{Eq, Show}

/** Everything a player can do on their turn.
  *
  * Taking the upcard at the start of a round and drawing from the pile on a later turn are the same
  * act, so [[Move.DrawDiscard]] covers both. Gin is not here: it is a [[Move.Knock]] that happens
  * to leave nothing behind, which keeps one guard where two would otherwise disagree about a hand
  * worth nothing.
  */
enum Move {
  case DrawStock
  case DrawDiscard
  case Pass
  case Discard(card: Card)
  case Knock(card: Card)
}

object Move {
  given Eq[Move] = Eq.fromUniversalEquals

  given Show[Move] = Show.fromToString
}
