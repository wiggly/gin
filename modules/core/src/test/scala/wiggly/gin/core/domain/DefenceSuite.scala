package wiggly.gin.core.domain

import cats.implicits.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.Rank.*
import wiggly.gin.core.domain.Suit.*
import wiggly.gin.gen.HandGen

object DefenceSuite extends SimpleIOSuite with Checkers {

  private def meldOf(cards: Card*): Meld =
    Meld.from(cards.toList).getOrElse(sys.error("the cards this test chose do not make a meld"))

  private def handOf(cards: Card*): Hand = Hand.of(cards.toList)

  /** Seven, eight and nine of spades, which every case below lays off onto or does not. */
  private val laidRun = meldOf(Card(Seven, Spades), Card(Eight, Spades), Card(Nine, Spades))

  private val laidSet = meldOf(Card(Nine, Hearts), Card(Nine, Diamonds), Card(Nine, Clubs))

  pureTest("a defender with nothing to lay off keeps the arrangement of their own hand") {
    val hand =
      handOf(Card(Two, Spades), Card(Three, Spades), Card(Four, Spades), Card(King, Hearts))
    val defence = Defence.against(hand, List(laidSet))

    expect.eql(
      defence.melds,
      List(meldOf(Card(Two, Spades), Card(Three, Spades), Card(Four, Spades)))
    ) and
      expect.eql(defence.layoffs, List.empty[Card]) and
      expect.eql(defence.deadwoodValue, 10)
  }

  pureTest("a defender lays the missing card onto the knocker's set") {
    val hand    = handOf(Card(Nine, Spades), Card(Four, Diamonds), Card(King, Clubs))
    val defence = Defence.against(hand, List(laidSet))

    expect.eql(defence.layoffs, List(Card(Nine, Spades))) and
      expect.eql(defence.deadwoodValue, 14)
  }

  pureTest("a defender lays a chain onto the end of the knocker's run") {
    val hand = handOf(
      Card(Ten, Spades),
      Card(Jack, Spades),
      Card(Two, Hearts),
      Card(Four, Diamonds),
      Card(Six, Clubs)
    )
    val defence = Defence.against(hand, List(laidRun))

    expect.eql(defence.layoffs, List(Card(Ten, Spades), Card(Jack, Spades))) and
      expect.eql(defence.deadwoodValue, 12)
  }

  pureTest("a run laid down can be extended at both ends") {
    val hand    = handOf(Card(Six, Spades), Card(Ten, Spades), Card(Two, Hearts))
    val defence = Defence.against(hand, List(laidRun))

    expect.eql(defence.layoffs, List(Card(Six, Spades), Card(Ten, Spades))) and
      expect.eql(defence.deadwoodValue, 2)
  }

  /** The bridge. Ten of spades is the only card that joins the jack to the knocker's run, and the
    * set of tens takes it, which strands the jack. Keeping the set is still the better answer
    * here, and the point of the case is that one search compares the two rather than committing
    * to the set before the layoff is known.
    */
  pureTest("a defender keeps a meld that is worth more than the layoff it blocks") {
    val hand =
      handOf(Card(Ten, Spades), Card(Ten, Hearts), Card(Ten, Diamonds), Card(Jack, Spades))
    val defence = Defence.against(hand, List(laidRun))

    expect.eql(
      defence.melds,
      List(meldOf(Card(Ten, Spades), Card(Ten, Hearts), Card(Ten, Diamonds)))
    ) and
      expect.eql(defence.layoffs, List.empty[Card]) and
      expect.eql(defence.deadwood, List(Card(Jack, Spades)))
  }

  test("every card is melded, laid off, or counted against the defender") {
    forall(HandGen.defenceAndKnock) { (hand, laid) =>
      val defence   = Defence.against(hand, laid)
      val accounted = defence.melds.flatMap(_.cards.toList) ++ defence.layoffs ++ defence.deadwood

      expect.eql(accounted.sorted, hand.cards)
    }
  }

  test("every meld the defender keeps is a meld") {
    forall(HandGen.defenceAndKnock) { (hand, laid) =>
      expect(Defence.against(hand, laid).melds.forall { meld =>
        Meld.from(meld.cards.toList).contains_(meld)
      })
    }
  }

  test("a defender is never worse off for the knocker having laid melds down") {
    forall(HandGen.defenceAndKnock) { (hand, laid) =>
      expect(Defence.against(hand, laid).deadwoodValue <= hand.deadwoodValue)
    }
  }
}
