package wiggly.gin.core.domain

import cats.implicits.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.gen.MatchGen

object MatchSuite extends SimpleIOSuite with Checkers {

  private val one = Player.One
  private val two = Player.Two

  private def playing(scores: RoundScore*): Match =
    scores.foldLeft(Match.start(one): Match) {
      case (round: Match.InProgress, score) => round.played(score)
      case (finished, score)                => sys.error(s"$finished was over before $score")
    }

  private def inProgress(round: Match): Match.InProgress = {
    round match {
      case round: Match.InProgress => round
      case finished                => sys.error(s"the match is already over: $finished")
    }
  }

  private def finished(round: Match): Match.Finished = {
    round match {
      case finished: Match.Finished => finished
      case round                    => sys.error(s"the match is still going: $round")
    }
  }

  pureTest("a match starts with both players on nothing and the dealer who was named") {
    val started = Match.start(one)

    expect.eql(started.totals, Tally(0, 0)) and
      expect.eql(started.roundsWon, Tally(0, 0)) and
      expect.eql(started.seats, Seats(one))
  }

  pureTest("the deal passes to the other player after a round that scored") {
    expect.eql(inProgress(playing(RoundScore.Knock(two, 30))).seats, Seats(two))
  }

  pureTest("a dead round is dealt again by the same dealer and scores nothing") {
    val dead = inProgress(playing(RoundScore.Dead))

    expect.eql(dead.seats, Seats(one)) and expect.eql(dead.totals, Tally(0, 0))
  }

  pureTest("the deal passes once for every round that scored and no more") {
    val three = inProgress(
      playing(RoundScore.Knock(one, 10), RoundScore.Dead, RoundScore.Knock(two, 10))
    )

    expect.eql(three.seats, Seats(one))
  }

  pureTest("a match keeps the rounds it played, most recent first") {
    val two_rounds = playing(RoundScore.Knock(one, 30), RoundScore.Gin(two, 40))

    expect.eql(two_rounds.rounds, List(RoundScore.Gin(two, 40), RoundScore.Knock(one, 30)))
  }

  pureTest("points add up round by round for the player who won each one") {
    val two_rounds = playing(RoundScore.Knock(one, 30), RoundScore.Gin(two, 40))

    expect.eql(two_rounds.totals, Tally(30, 40)) and
      expect.eql(two_rounds.roundsWon, Tally(1, 1))
  }

  pureTest("a match is still going while both players are under a hundred") {
    val ninety = playing(RoundScore.Knock(one, 45), RoundScore.Knock(one, 45))

    expect.eql(inProgress(ninety).totals, Tally(90, 0))
  }

  pureTest("a match ends the moment a player reaches a hundred") {
    val crossed = finished(
      playing(RoundScore.Knock(one, 45), RoundScore.Knock(one, 45), RoundScore.Knock(one, 10))
    )

    expect.eql(crossed.result.winner, one)
  }

  pureTest("the winner takes the game bonus and both players take a box for each round won") {
    val over = finished(
      playing(RoundScore.Knock(one, 60), RoundScore.Knock(two, 20), RoundScore.Knock(one, 45))
    )

    expect.eql(over.result, MatchResult(one, Tally(105 + 100 + 50, 20 + 25)))
  }

  pureTest("a winner who left the other player on nothing doubles their own figure") {
    val shutout = finished(playing(RoundScore.Knock(one, 60), RoundScore.Knock(one, 45)))

    expect.eql(shutout.result, MatchResult(one, Tally((105 + 100 + 50) * 2, 0)))
  }

  test("playing a round never takes points away from either player") {
    forall(MatchGen.playedAndNext) {
      case (round: Match.InProgress, score) => {
        val next = round.played(score)

        expect(Player.values.forall(player => next.totals(player) >= round.totals(player)))
      }
      case (finished, _) => failure(s"the match was over before the next round: $finished")
    }
  }

  test("a match that is still going has nobody at the target yet") {
    forall(MatchGen.played) { played =>
      played match {
        case round: Match.InProgress =>
          expect(Player.values.forall(player => round.totals(player) < Match.Target))
        case _: Match.Finished => success
      }
    }
  }

  test("a match that is over has exactly one player at the target") {
    forall(MatchGen.played) { played =>
      played match {
        case over: Match.Finished =>
          expect.eql(over.totals(over.result.winner) >= Match.Target, true) and
            expect.eql(over.totals(over.result.winner.other) < Match.Target, true)
        case _: Match.InProgress => success
      }
    }
  }

  test("the totals hold the points of the rounds played and nothing besides") {
    forall(MatchGen.played) { played =>
      expect.eql(
        played.totals(one) + played.totals(two),
        played.rounds.flatMap(_.scored.map(_._2)).sum
      )
    }
  }
}
