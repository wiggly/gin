package wiggly.gin.core.domain

import cats.implicits.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.Rank.*
import wiggly.gin.core.domain.Suit.*
import wiggly.gin.gen.{CardGen, HandGen}

object ArrangementSuite extends SimpleIOSuite with Checkers {

  test("every card in the hand is either melded or deadwood, and none is used twice") {
    forall(HandGen.hand) { hand =>
      val arrangement = Arrangement.best(hand)
      val accounted   = arrangement.melds.flatMap(_.cards.toList) ++ arrangement.deadwood

      expect.eql(accounted.sorted, hand.sorted)
    }
  }

  test("every meld it returns is a meld") {
    forall(HandGen.hand) { hand =>
      val melds = Arrangement.best(hand).melds

      expect(melds.forall(meld => Meld.from(meld.cards.toList).contains_(meld)))
    }
  }

  test("no arrangement built by hand beats the one it finds") {
    forall(HandGen.handWithKnownDeadwood()) { (hand, knownDeadwood) =>
      expect(Arrangement.best(hand).deadwoodValue <= knownDeadwood)
    }
  }

  test("taking one more card costs at most what that card is worth") {
    val handAndDraw = CardGen.distinctCards(HandGen.HandSize + 1).map(cards => cards.splitAt(10))

    forall(handAndDraw) { (hand, drawn) =>
      val before = Arrangement.best(hand).deadwoodValue
      val after  = Arrangement.best(hand ++ drawn).deadwoodValue

      expect(after <= before + drawn.map(_.deadwoodValue).sum)
    }
  }

  test("the order the cards arrive in makes no difference") {
    forall(HandGen.hand) { hand =>
      expect.eql(Arrangement.best(hand.reverse), Arrangement.best(hand))
    }
  }

  pureTest("a card wanted by two melds goes to the one that leaves less behind") {
    // The eight of diamonds can finish the run or the set, but not both. The run leaves the two
    // black eights, worth sixteen; the set would leave the nine and ten, worth nineteen.
    val hand = List(
      Card(Eight, Diamonds),
      Card(Nine, Diamonds),
      Card(Ten, Diamonds),
      Card(Eight, Spades),
      Card(Eight, Hearts)
    )

    val arrangement = Arrangement.best(hand)

    expect.eql(arrangement.deadwoodValue, 16) and
      expect.eql(arrangement.deadwood, List(Card(Eight, Hearts), Card(Eight, Spades)).sorted)
  }

  pureTest("a long run gives up a card when a set can use it better") {
    // Melding the whole diamond run leaves two sevens stranded. Shortening it to three feeds the
    // fourth card to the set and nothing is left over at all.
    val hand = List(
      Card(Four, Diamonds),
      Card(Five, Diamonds),
      Card(Six, Diamonds),
      Card(Seven, Diamonds),
      Card(Seven, Hearts),
      Card(Seven, Clubs)
    )

    val arrangement = Arrangement.best(hand)

    expect.eql(arrangement.deadwoodValue, 0) and expect.eql(arrangement.melds.size, 2)
  }

  pureTest("a set gives up whichever card the run needs, not whichever comes first") {
    // Three of the four sevens make the set; which three is not free, because the heart is the one
    // the run cannot do without.
    val hand = List(
      Card(Seven, Clubs),
      Card(Seven, Diamonds),
      Card(Seven, Hearts),
      Card(Seven, Spades),
      Card(Eight, Hearts),
      Card(Nine, Hearts)
    )

    val arrangement = Arrangement.best(hand)

    expect.eql(arrangement.deadwoodValue, 0) and expect.eql(arrangement.melds.size, 2)
  }

  pureTest("a hand that melds completely is gin") {
    val hand = List(
      Card(Two, Hearts),
      Card(Three, Hearts),
      Card(Four, Hearts),
      Card(Seven, Spades),
      Card(Seven, Hearts),
      Card(Seven, Diamonds),
      Card(Seven, Clubs),
      Card(Nine, Clubs),
      Card(Ten, Clubs),
      Card(Jack, Clubs)
    )

    val arrangement = Arrangement.best(hand)

    expect.eql(arrangement.deadwoodValue, 0) and expect(arrangement.deadwood.isEmpty)
  }

  pureTest("the knock boundary is a matter of one pip") {
    val melded = List(
      Card(Two, Hearts),
      Card(Three, Hearts),
      Card(Four, Hearts),
      Card(Seven, Spades),
      Card(Seven, Hearts),
      Card(Seven, Diamonds)
    )

    val canKnock =
      melded ++ List(Card(Ace, Spades), Card(Two, Spades), Card(Three, Clubs), Card(Four, Clubs))
    val cannot =
      melded ++ List(Card(Ace, Spades), Card(Two, Spades), Card(Three, Clubs), Card(Five, Clubs))

    expect.eql(Arrangement.best(canKnock).deadwoodValue, 10) and
      expect.eql(Arrangement.best(cannot).deadwoodValue, 11)
  }

  pureTest("the most tangled hand the deck can deal is still brute force's to solve") {
    // Eleven cards of one suit is the longest run a hand can hold and the hand with the most
    // overlapping candidates, so it is where a naive search would be felt if anywhere.
    val hand = Rank.values.toList.take(HandGen.HandSize + 1).map(Card(_, Diamonds))

    expect.eql(Arrangement.best(hand).deadwoodValue, 0)
  }

  pureTest("a hand with nothing in it is worth every card it holds") {
    val hand = List(
      Card(Ace, Spades),
      Card(Three, Hearts),
      Card(Five, Diamonds),
      Card(Seven, Clubs),
      Card(Nine, Spades),
      Card(Jack, Hearts),
      Card(King, Diamonds),
      Card(Two, Clubs),
      Card(Four, Spades),
      Card(Six, Hearts)
    )

    val arrangement = Arrangement.best(hand)

    expect(arrangement.melds.isEmpty) and
      expect.eql(arrangement.deadwoodValue, hand.map(_.deadwoodValue).sum) and
      expect.eql(arrangement.deadwoodValue, 57)
  }
}
