package wiggly.gin.core.domain

import cats.{Eq, Show}

/** All 52 cards, each of them once, in some order.
  *
  * The order is the order they will be drawn in, so a shuffled deck and the ordered one are the
  * same type. What the type rules out is a deck that repeats a card, is short of one, or holds
  * something from a second pack. Nothing after the deal ever makes a card, so this is the only
  * place a malformed set of cards can enter the game.
  */
final case class Deck private (cards: List[Card]) {
  def size: Int = cards.size
}

object Deck {

  /** The 52 cards in a fixed, known order.
    *
    * Deliberately not shuffled: shuffling is an effect, and it enters the game as a port that
    * hands [[wiggly.gin.core.domain.GameState.deal]] a deck it has already shuffled. That keeps
    * dealing testable against a known arrangement of cards.
    */
  val ordered: Deck = {
    new Deck(
      for {
        suit <- Suit.values.toList
        rank <- Rank.values.toList
      } yield Card(rank, suit)
    )
  }

  /** These cards as a deck, if they are one.
    *
    * A list is a deck exactly when sorting it gives [[ordered]] back, which rejects a repeated
    * card, a wrong count and a foreign card in one comparison. A length check alone would let
    * through 52 cards holding one twice and another not at all.
    */
  def from(cards: List[Card]): Option[Deck] =
    Option.when(cards.sorted == ordered.cards)(new Deck(cards))

  given Eq[Deck] = Eq.fromUniversalEquals

  given Show[Deck] = Show.fromToString
}
