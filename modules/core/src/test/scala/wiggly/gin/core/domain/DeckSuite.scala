package wiggly.gin.core.domain

import weaver.SimpleIOSuite

object DeckSuite extends SimpleIOSuite {

  pureTest("the deck holds fifty-two distinct cards") {
    expect.eql(Deck.ordered.size, 52) and expect.eql(Deck.ordered.distinct.size, 52)
  }

  pureTest("every suit and rank pairing appears exactly once") {
    val expected = for {
      suit <- Suit.values.toList
      rank <- Rank.values.toList
    } yield Card(rank, suit)

    expect.eql(Deck.ordered.toSet, expected.toSet) and
      expect.eql(Deck.ordered.groupBy(_.suit).view.mapValues(_.size).toMap.values.toSet, Set(13))
  }
}
