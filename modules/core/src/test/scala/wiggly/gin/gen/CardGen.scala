package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.domain.{Card, Deck, Rank, Suit}

/** Generators for the card model, shared with the other modules through the `test->test` dependency
  * in `build.sbt`.
  */
object CardGen {

  val rank: Gen[Rank] = Gen.oneOf(Rank.values.toList)

  val suit: Gen[Suit] = Gen.oneOf(Suit.values.toList)

  val card: Gen[Card] = Gen.oneOf(Deck.ordered.cards)

  /** `n` distinct cards, dealt from the deck without replacement. */
  def distinctCards(n: Int): Gen[List[Card]] = Gen.pick(n, Deck.ordered.cards).map(_.toList)

  /** All 52 cards in an arbitrary order, which is what a shuffle produces. */
  val shuffledDeck: Gen[List[Card]] = distinctCards(Deck.ordered.cards.size)
}
