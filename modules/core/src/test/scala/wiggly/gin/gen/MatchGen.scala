package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.domain.{Match, Player, RoundScore}

object MatchGen {

  val score: Gen[RoundScore] = for {
    winner <- Gen.oneOf(Player.values.toList)
    points <- Gen.choose(1, 40)
    score  <- Gen.oneOf(
      RoundScore.Knock(winner, points),
      RoundScore.Gin(winner, points),
      RoundScore.Undercut(winner, points),
      RoundScore.Dead
    )
  } yield score

  /** A match some rounds in, built by playing scores into it rather than by assembling one, which
    * is also the only way to build one: a finished match cannot be written down by hand.
    */
  val played: Gen[Match] = for {
    dealer <- Gen.oneOf(Player.values.toList)
    scores <- Gen.listOf(score)
  } yield scores.foldLeft(Match.start(dealer): Match) {
    case (round: Match.InProgress, score) => round.played(score)
    case (finished, _)                    => finished
  }

  /** A match that still has rounds to play, and the score of the next one.
    *
    * It stops short of the round that would end the match, which is what keeps it in progress.
    */
  val playedAndNext: Gen[(Match, RoundScore)] = for {
    dealer <- Gen.oneOf(Player.values.toList)
    scores <- Gen.listOf(score)
    next   <- score
  } yield {
    val round = scores.foldLeft(Match.start(dealer)) { (round, score) =>
      round.played(score) match {
        case playing: Match.InProgress => playing
        case _: Match.Finished         => round
      }
    }

    (round, next)
  }
}
