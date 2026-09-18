package wiggly.gin.core.domain

import cats.implicits.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.Rank.*
import wiggly.gin.core.domain.Suit.*
import wiggly.gin.gen.GameGen

object RoundResultSuite extends SimpleIOSuite with Checkers {

  private val seats   = Seats(Player.Two)
  private val knocker = seats.nonDealer
  private val holder  = seats.dealer

  private def resultOf(knocks: List[Card], holds: List[Card]): RoundResult =
    RoundResult.of(GameGen.knockKeeping(seats, knocks, holds))

  private def scoreOf(knocks: List[Card], holds: List[Card]): RoundScore =
    resultOf(knocks, holds).score

  /** Two runs and four loose cards worth ten between them, which is a knock right on the mark. */
  private val tenOfDeadwood = List(
    Card(Ace, Spades),
    Card(Two, Spades),
    Card(Three, Spades),
    Card(Five, Hearts),
    Card(Six, Hearts),
    Card(Seven, Hearts),
    Card(Ace, Clubs),
    Card(Three, Clubs),
    Card(Two, Diamonds),
    Card(Four, Diamonds)
  )

  /** Ace to ten of spades: one run, nothing left over. */
  private val gin = Rank.values.toList.take(GameState.HandSize).map(Card(_, Spades))

  /** A run in hearts and seven loose cards worth forty-two, none of which reaches either of the
    * knocker's runs.
    */
  private val fortyTwoOfDeadwood = List(
    Card(King, Hearts),
    Card(Queen, Hearts),
    Card(Jack, Hearts),
    Card(Nine, Spades),
    Card(Seven, Diamonds),
    Card(Five, Clubs),
    Card(Nine, Hearts),
    Card(Two, Hearts),
    Card(Four, Clubs),
    Card(Six, Diamonds)
  )

  /** Two runs and four loose cards worth ten, which meets the knocker exactly. */
  private val tenAgainstTheKnock = List(
    Card(King, Diamonds),
    Card(Queen, Diamonds),
    Card(Jack, Diamonds),
    Card(Ten, Clubs),
    Card(Nine, Clubs),
    Card(Eight, Clubs),
    Card(Ace, Hearts),
    Card(Two, Clubs),
    Card(Three, Diamonds),
    Card(Four, Clubs)
  )

  /** The same hand with the four of clubs swapped for the five, one pip the wrong side of it. */
  private val elevenAgainstTheKnock = tenAgainstTheKnock.init :+ Card(Five, Clubs)

  /** Two cards that would go straight onto the knocker's run, and eight that would not. */
  private val couldLayOffOntoGin = List(
    Card(Jack, Spades),
    Card(Queen, Spades),
    Card(King, Hearts),
    Card(Nine, Hearts),
    Card(Seven, Diamonds),
    Card(Five, Clubs),
    Card(Three, Diamonds),
    Card(Two, Clubs),
    Card(Four, Hearts),
    Card(Six, Diamonds)
  )

  pureTest("a knocker takes the difference between the two hands") {
    expect.eql(scoreOf(tenOfDeadwood, fortyTwoOfDeadwood), RoundScore.Knock(knocker, 32))
  }

  pureTest("a knocker beaten by one pip still takes the difference") {
    expect.eql(scoreOf(tenOfDeadwood, elevenAgainstTheKnock), RoundScore.Knock(knocker, 1))
  }

  pureTest("a defender who matches the knocker undercuts them") {
    expect.eql(scoreOf(tenOfDeadwood, tenAgainstTheKnock), RoundScore.Undercut(holder, 25))
  }

  pureTest("gin takes the whole of the other hand and twenty-five on top") {
    expect.eql(scoreOf(gin, couldLayOffOntoGin), RoundScore.Gin(knocker, 91))
  }

  pureTest("gin allows no layoffs, so the cards that would have gone onto the run still count") {
    val result = resultOf(gin, couldLayOffOntoGin)

    expect.eql(result.defender.map(_.layoffs), Some(List.empty[Card])) and
      expect.eql(result.score.scored.map(_._2), Some(66 + 25))
  }

  pureTest("a knock puts both hands on the table") {
    val result = resultOf(tenOfDeadwood, fortyTwoOfDeadwood)

    expect.eql(result.outcome, Outcome.Knocked(knocker)) and
      expect.eql(result.knocker.map(_.deadwoodValue), Some(10)) and
      expect.eql(result.defender.map(_.deadwoodValue), Some(42))
  }

  pureTest("a knocker's melds are the ones the other player lays off onto") {
    val result = resultOf(tenOfDeadwood, tenAgainstTheKnock)

    expect.eql(result.knocker.map(_.melds.size), Some(2))
  }

  test("a round that ran out of stock reveals nothing and scores nothing") {
    forall(GameGen.ranOutOfStock) {
      case round: GameState.Finished =>
        val result = RoundResult.of(round)

        expect.eql(result.outcome, Outcome.Dead) and
          expect.eql(result.knocker, None) and
          expect.eql(result.defender, None) and
          expect.eql(result.score, RoundScore.Dead)
      case round: GameState.InProgress => failure(s"the round never ran out of stock: $round")
    }
  }

  test("the result of a round agrees with the way the round ended") {
    forall(GameGen.walked()) { state =>
      state match {
        case round: GameState.Finished =>
          round.outcome match {
            case Outcome.Dead       => expect.eql(RoundResult.of(round).score.scored, None)
            case Outcome.Knocked(_) => expect(RoundResult.of(round).score.scored.isDefined)
          }
        case _: GameState.InProgress => success
      }
    }
  }

  test("no round is ever worth a negative number of points") {
    forall(GameGen.walked()) { state =>
      state match {
        case round: GameState.Finished =>
          expect(RoundResult.of(round).score.scored.forall((_, points) => points >= 0))
        case _: GameState.InProgress => success
      }
    }
  }

  test("the knocker wins the round unless the other player undercut them") {
    forall(GameGen.walked()) { state =>
      state match {
        case round: GameState.Finished =>
          (round.outcome, RoundResult.of(round).score) match {
            case (Outcome.Knocked(who), RoundScore.Undercut(winner, _)) =>
              expect.eql(winner, who.other)
            case (Outcome.Knocked(who), score) => expect.eql(score.scored.map(_._1), Some(who))
            case (Outcome.Dead, score)         => expect.eql(score, RoundScore.Dead)
          }
        case _: GameState.InProgress => success
      }
    }
  }

  test("what a knock reveals is exactly the two hands that were held") {
    forall(GameGen.walked()) { state =>
      state match {
        case round: GameState.Finished =>
          round.outcome match {
            case Outcome.Dead         => success
            case Outcome.Knocked(who) => {
              val result   = RoundResult.of(round)
              val knocked  = result.knocker.toList.flatMap(shown)
              val answered = result.defender.toList.flatMap(held)

              expect.eql(knocked.sorted, round.table.hands(who).cards) and
                expect.eql(answered.sorted, round.table.hands(who.other).cards)
            }
          }
        case _: GameState.InProgress => success
      }
    }
  }

  private def shown(arrangement: Arrangement): List[Card] =
    arrangement.melds.flatMap(_.cards.toList) ++ arrangement.deadwood

  private def held(defence: Defence): List[Card] =
    defence.melds.flatMap(_.cards.toList) ++ defence.layoffs ++ defence.deadwood
}
