package wiggly.gin.core.domain

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.Rank.*
import wiggly.gin.core.domain.Suit.*
import wiggly.gin.gen.{CardGen, HandGen}

object HandSuite extends SimpleIOSuite with Checkers {

  test("a hand holds its cards in canonical order however they arrive") {
    forall(HandGen.hand) { cards =>
      expect.eql(Hand.of(cards).cards, cards.sorted)
    }
  }

  test("taking a card and then putting it back gives the hand it started with") {
    val handAndDraw = CardGen.distinctCards(HandGen.HandSize + 1).map(_.splitAt(HandGen.HandSize))

    forall(handAndDraw) { (cards, drawn) =>
      val hand = Hand.of(cards)

      expect.eql(drawn.foldLeft(hand)(_.add(_)).remove(drawn.head), Some(hand))
    }
  }

  test("a card the hand does not hold cannot be removed") {
    val handAndMissing =
      CardGen.distinctCards(HandGen.HandSize + 1).map(_.splitAt(HandGen.HandSize))

    forall(handAndMissing) { (cards, missing) =>
      expect.eql(Hand.of(cards).remove(missing.head), None)
    }
  }

  pureTest("removing a card held twice takes one copy, not both") {
    val repeated = Card(Seven, Hearts)
    val hand     = Hand.of(List(repeated, repeated, Card(Two, Clubs)))

    expect.eql(hand.remove(repeated).map(_.cards), Some(List(repeated, Card(Two, Clubs))))
  }

  test("what a hand is worth is what the best arrangement of its cards leaves") {
    forall(HandGen.hand) { cards =>
      expect.eql(Hand.of(cards).deadwoodValue, Arrangement.best(cards).deadwoodValue)
    }
  }

  test("changing one player's hand leaves the other player's alone") {
    val twoHands = CardGen.distinctCards(HandGen.HandSize * 2 + 1).map { cards =>
      val (dealer, rest) = cards.splitAt(HandGen.HandSize)

      (Hand.of(dealer), Hand.of(rest.tail), rest.head)
    }

    forall(twoHands) { (dealer, nonDealer, spare) =>
      val hands   = Hands(dealer, nonDealer)
      val changed = hands.updated(Player.Dealer, dealer.add(spare))

      expect.eql(changed(Player.Dealer), dealer.add(spare)) and
        expect.eql(changed(Player.NonDealer), nonDealer)
    }
  }
}
