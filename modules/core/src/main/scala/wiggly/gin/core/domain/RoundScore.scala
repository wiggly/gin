package wiggly.gin.core.domain

import cats.syntax.all.*
import cats.{Eq, Show}

/** What a finished round was worth, and to whom.
  *
  * The three ways of winning are named rather than collapsed into a winner and a number, because
  * the match counts the rounds a player won and a dead round must not be one of them, and because
  * a test that pins a bonus reads as the rule it comes from rather than as a suspicious 25.
  */
enum RoundScore {

  /** The stock ran down. Neither player scores and the cards are dealt again. */
  case Dead

  /** The knocker was left holding less than the other player and takes the difference. */
  case Knock(winner: Player, points: Int)

  /** The knocker was left holding nothing at all. */
  case Gin(winner: Player, points: Int)

  /** The other player met or beat the knocker's deadwood and takes the round instead. */
  case Undercut(winner: Player, points: Int)

  /** Who scored and what they scored. A dead round scores for nobody, which is the one thing every
    * reader of a score has to handle and so the one thing this does not let them forget.
    */
  def scored: Option[(Player, Int)] = {
    this match {
      case Dead                     => None
      case Knock(winner, points)    => Some((winner, points))
      case Gin(winner, points)      => Some((winner, points))
      case Undercut(winner, points) => Some((winner, points))
    }
  }
}

object RoundScore {

  /** What a knocker takes on top of the difference for being left with nothing. */
  val GinBonus: Int = 25

  /** What the other player takes on top of the difference for meeting or beating the knocker. */
  val UndercutBonus: Int = 25

  /** The score of a round that is over.
    *
    * Gin is settled before any layoff is worked out, which is how the rule that gin blocks layoffs
    * becomes an order of evaluation rather than a flag that something else has to read.
    */
  def of(round: GameState.Finished): RoundScore = {
    round.outcome match {
      case Outcome.Dead             => Dead
      case Outcome.Knocked(knocker) => {
        val knocked  = round.table.hands(knocker).arrangement
        val defender = knocker.other
        val hand     = round.table.hands(defender)

        if (knocked.deadwoodValue === 0) Gin(knocker, hand.deadwoodValue + GinBonus)
        else {
          val left = Defence.against(hand, knocked.melds).deadwoodValue

          if (left <= knocked.deadwoodValue)
            Undercut(defender, knocked.deadwoodValue - left + UndercutBonus)
          else Knock(knocker, left - knocked.deadwoodValue)
        }
      }
    }
  }

  given Eq[RoundScore] = Eq.fromUniversalEquals

  given Show[RoundScore] = Show.fromToString
}
