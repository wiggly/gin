package wiggly.gin.core.domain

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.Rank.*
import wiggly.gin.core.domain.Suit.*
import wiggly.gin.gen.CardGen

object DeckSuite extends SimpleIOSuite with Checkers {

  pureTest("the deck holds fifty-two distinct cards") {
    expect.eql(Deck.ordered.cards.size, 52) and
      expect.eql(Deck.ordered.cards.distinct.size, 52)
  }

  pureTest("every suit and rank pairing appears exactly once") {
    val expected = for {
      suit <- Suit.values.toList
      rank <- Rank.values.toList
    } yield Card(rank, suit)

    expect.eql(Deck.ordered.cards.toSet, expected.toSet) and
      expect.eql(
        Deck.ordered.cards.groupBy(_.suit).view.mapValues(_.size).toMap.values.toSet,
        Set(13)
      )
  }

  test("any shuffling of the fifty-two is a deck, in the order it was given") {
    forall(CardGen.shuffledDeck) { shuffled =>
      expect.eql(Deck.from(shuffled).map(_.cards), Some(shuffled))
    }
  }

  pureTest("a deck short of a card is not a deck") {
    expect.eql(Deck.from(Deck.ordered.cards.tail), None)
  }

  pureTest("fifty-three cards are not a deck") {
    expect.eql(Deck.from(Card(Ace, Spades) :: Deck.ordered.cards), None)
  }

  pureTest("fifty-two cards holding one twice and another not at all are not a deck") {
    val swapped = Card(Ace, Spades) :: Card(Ace, Spades) :: Deck.ordered.cards.tail.tail

    expect.eql(swapped.size, 52) and expect.eql(Deck.from(swapped), None)
  }
}
