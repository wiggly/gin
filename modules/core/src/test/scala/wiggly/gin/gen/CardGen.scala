package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.{Card, Deck, Rank, Suit}

/** Generators for the card model, shared with the other modules through the `test->test` dependency
  * in `build.sbt`.
  */
object CardGen {

  val rank: Gen[Rank] = Gen.oneOf(Rank.values.toList)

  val suit: Gen[Suit] = Gen.oneOf(Suit.values.toList)

  val card: Gen[Card] = Gen.oneOf(Deck.ordered)

  /** `n` distinct cards, dealt from the deck without replacement. */
  def distinctCards(n: Int): Gen[List[Card]] = Gen.pick(n, Deck.ordered).map(_.toList)
}
