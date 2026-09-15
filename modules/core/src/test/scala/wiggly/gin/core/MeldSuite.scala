package wiggly.gin.core

import cats.implicits.*
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.Rank.*
import wiggly.gin.core.Suit.*
import wiggly.gin.gen.MeldGen

object MeldSuite extends SimpleIOSuite with Checkers {

  test("a meld's own cards make that same meld again") {
    forall(MeldGen.meld) { meld =>
      expect.eql(Meld.from(meld.cards.toList), Some(meld))
    }
  }

  test("cards make the same meld whatever order they arrive in") {
    val shuffled = MeldGen.meld.flatMap(meld =>
      Gen.pick(meld.cards.length, meld.cards.toList).map(meld -> _.toList)
    )

    forall(shuffled) { (meld, cards) =>
      expect.eql(Meld.from(cards), Some(meld))
    }
  }

  test("a meld is worth the sum of its cards") {
    forall(MeldGen.meld) { meld =>
      expect.eql(meld.deadwoodValue, meld.cards.toList.map(_.deadwoodValue).sum)
    }
  }

  pureTest("three of a rank in different suits is a set") {
    val cards = List(Card(Seven, Spades), Card(Seven, Hearts), Card(Seven, Clubs))

    Meld.from(cards) match {
      case Some(set: Meld.Set) => expect.eql(set.rank, Seven)
      case other               => failure(show"expected a set, got: $other")
    }
  }

  pureTest("three of a suit in sequence is a run") {
    val cards = List(Card(Five, Hearts), Card(Six, Hearts), Card(Seven, Hearts))

    Meld.from(cards) match {
      case Some(run: Meld.Run) =>
        expect.eql(run.suit, Hearts) and expect.eql(run.lowest, Five) and
          expect.eql(run.highest, Seven)
      case other => failure(show"expected a run, got: $other")
    }
  }

  pureTest("two cards are never enough, of either kind") {
    expect.eql(Meld.from(List(Card(Seven, Spades), Card(Seven, Hearts))), None) and
      expect.eql(Meld.from(List(Card(Five, Hearts), Card(Six, Hearts))), None)
  }

  pureTest("a set cannot use the same suit twice") {
    val cards = List(Card(Seven, Spades), Card(Seven, Spades), Card(Seven, Hearts))

    expect.eql(Meld.from(cards), None)
  }

  pureTest("a run cannot cross suits or skip a rank") {
    val crossesSuits = List(Card(Five, Hearts), Card(Six, Spades), Card(Seven, Hearts))
    val skipsARank   = List(Card(Five, Hearts), Card(Six, Hearts), Card(Eight, Hearts))

    expect.eql(Meld.from(crossesSuits), None) and expect.eql(Meld.from(skipsARank), None)
  }

  pureTest("a run does not turn the corner from the king into the ace") {
    val overTheTop  = List(Card(Queen, Hearts), Card(King, Hearts), Card(Ace, Hearts))
    val aroundAgain = List(Card(King, Hearts), Card(Ace, Hearts), Card(Two, Hearts))

    expect.eql(Meld.from(overTheTop), None) and expect.eql(Meld.from(aroundAgain), None)
  }

  pureTest("a run may be longer than three cards, up to the whole suit") {
    val wholeSuit = Rank.values.toList.map(Card(_, Diamonds))

    expect(Meld.from(wholeSuit).exists(_.cards.length === 13))
  }
}
