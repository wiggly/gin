package wiggly.gin.core.domain

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.gen.CardGen

object RankSuite extends SimpleIOSuite with Checkers {

  pureTest("the ace counts one and every court card counts ten") {
    expect.eql(Rank.Ace.deadwoodValue, 1) and
      expect.eql(Rank.Ten.deadwoodValue, 10) and
      expect.eql(Rank.Jack.deadwoodValue, 10) and
      expect.eql(Rank.Queen.deadwoodValue, 10) and
      expect.eql(Rank.King.deadwoodValue, 10)
  }

  pureTest("the ace is low in a run and the king is high") {
    expect.eql(Rank.Ace.order, 1) and
      expect.eql(Rank.King.order, 13) and
      expect.eql(Rank.values.length, 13)
  }

  test("no card is ever worth less than an ace or more than ten") {
    forall(CardGen.rank) { rank =>
      expect(rank.deadwoodValue >= 1) and expect(rank.deadwoodValue <= 10)
    }
  }

  test("below the jack a rank is worth its position in a run") {
    forall(Gen.oneOf(Rank.values.toList.filter(_.order < Rank.Jack.order))) { rank =>
      expect.eql(rank.deadwoodValue, rank.order)
    }
  }

  test("the next rank is one step up the run") {
    forall(CardGen.rank) { rank =>
      rank.next match {
        case Some(next) => expect.eql(next.order, rank.order + 1)
        case None       => expect.eql(rank, Rank.King)
      }
    }
  }

  pureTest("a run does not wrap around from the king back to the ace") {
    expect(Rank.King.next.isEmpty) and expect.eql(Rank.Queen.next, Some(Rank.King))
  }
}
