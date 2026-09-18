package wiggly.gin.core.domain

import cats.implicits.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.Rank.*
import wiggly.gin.core.domain.Suit.*
import wiggly.gin.gen.GameGen

object RoundScoreSuite extends SimpleIOSuite with Checkers {

  private val seats   = Seats(Player.Two)
  private val knocker = seats.nonDealer
  private val holder  = seats.dealer

  /** A card neither hand below wants, thrown away to end the turn the knock happens on. */
  private val spare = Card(King, Clubs)

  /** A real deal arranged so that the knocker ends holding `knocks` and the other player holds
    * `holds`, so that every score below is the score of a round that was actually played.
    */
  private def scoreOf(knocks: List[Card], holds: List[Card]): RoundScore = {
    val upcard = knocks.last
    val dealt  = knocks.init :+ spare
    val rest   = Deck.ordered.cards.diff(dealt ++ holds ++ List(upcard))

    val deck = Deck
      .from(dealt ++ holds ++ List(upcard) ++ rest)
      .getOrElse(sys.error("the cards this test chose do not make a deck"))

    val played = for {
      taken <- GameState(GameState.deal(deck, seats), knocker, Move.DrawDiscard)
      ended <- GameState(taken, knocker, Move.Knock(spare))
    } yield ended

    played match {
      case Right(round: GameState.Finished) => RoundScore.of(round)
      case other => sys.error(s"the cards this test chose did not reach a knock: $other")
    }
  }

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
    expect.eql(scoreOf(gin, couldLayOffOntoGin).scored.map(_._2), Some(66 + 25))
  }

  test("a round that ran out of stock scores for nobody") {
    forall(GameGen.ranOutOfStock) {
      case round: GameState.Finished   => expect.eql(RoundScore.of(round), RoundScore.Dead)
      case round: GameState.InProgress => failure(s"the round never ran out of stock: $round")
    }
  }

  test("the score of a round agrees with the way the round ended") {
    forall(GameGen.walked()) { state =>
      state match {
        case round: GameState.Finished =>
          round.outcome match {
            case Outcome.Dead       => expect.eql(RoundScore.of(round).scored, None)
            case Outcome.Knocked(_) => expect(RoundScore.of(round).scored.isDefined)
          }
        case _: GameState.InProgress => success
      }
    }
  }

  test("no round is ever worth a negative number of points") {
    forall(GameGen.walked()) { state =>
      state match {
        case round: GameState.Finished =>
          expect(RoundScore.of(round).scored.forall((_, points) => points >= 0))
        case _: GameState.InProgress => success
      }
    }
  }

  test("the knocker wins the round unless the other player undercut them") {
    forall(GameGen.walked()) { state =>
      state match {
        case round: GameState.Finished =>
          (round.outcome, RoundScore.of(round)) match {
            case (Outcome.Knocked(who), RoundScore.Undercut(winner, _)) =>
              expect.eql(winner, who.other)
            case (Outcome.Knocked(who), score) => expect.eql(score.scored.map(_._1), Some(who))
            case (Outcome.Dead, score)         => expect.eql(score, RoundScore.Dead)
          }
        case _: GameState.InProgress => success
      }
    }
  }
}
