package wiggly.gin.core

object Deck {

  /** The 52 cards in a fixed, known order.
    *
    * Deliberately not shuffled: shuffling is an effect, and it enters the game as a port in the
    * state machine, which is dealt an already-shuffled deck. That keeps dealing testable against a
    * known arrangement of cards.
    */
  val ordered: List[Card] = {
    for {
      suit <- Suit.values.toList
      rank <- Rank.values.toList
    } yield Card(rank, suit)
  }
}
