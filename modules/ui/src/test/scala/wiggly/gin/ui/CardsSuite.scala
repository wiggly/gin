package wiggly.gin.ui

import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{Card, Rank, Suit}

object CardsSuite extends SimpleIOSuite {

  pureTest("a card is named the way a person says it") {
    expect
      .same("nine of spades", Cards.name(Card(Rank.Nine, Suit.Spades)))
      .and(expect.same("queen of hearts", Cards.name(Card(Rank.Queen, Suit.Hearts))))
  }

  pureTest("a card's short name is its corner index") {
    expect
      .same("9♠", Cards.short(Card(Rank.Nine, Suit.Spades)))
      .and(expect.same("10♦", Cards.short(Card(Rank.Ten, Suit.Diamonds))))
      .and(expect.same("A♣", Cards.short(Card(Rank.Ace, Suit.Clubs))))
      .and(expect.same("K♥", Cards.short(Card(Rank.King, Suit.Hearts))))
  }
}
