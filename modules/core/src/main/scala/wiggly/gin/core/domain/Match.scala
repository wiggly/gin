package wiggly.gin.core.domain

import cats.syntax.all.*
import cats.{Eq, Show}

/** A number for each player, keyed so that a lookup cannot fail. */
final case class Tally(one: Int, two: Int) {

  def apply(player: Player): Int = {
    player match {
      case Player.One => one
      case Player.Two => two
    }
  }

  def plus(player: Player, amount: Int): Tally = {
    player match {
      case Player.One => copy(one = one + amount)
      case Player.Two => copy(two = two + amount)
    }
  }
}

object Tally {
  val nothing: Tally = Tally(0, 0)

  given Eq[Tally] = Eq.fromUniversalEquals

  given Show[Tally] = Show.fromToString
}

/** How a match came out: who won, and what each player finished on once the bonuses were added. */
final case class MatchResult(winner: Player, totals: Tally)

object MatchResult {
  given Eq[MatchResult] = Eq.fromUniversalEquals

  given Show[MatchResult] = Show.fromToString
}

/** A sequence of rounds between the same two players, run to a target.
  *
  * A match is the seating the first round was dealt with and the rounds played since, and nothing
  * else. The totals, the rounds each player won and who deals next are all arithmetic over that
  * list, so none of them can drift out of step with the rounds they come from.
  *
  * It does not hold the round being played. Whoever holds a game deals each round with [[seats]]
  * and plays the score back in when the round ends.
  */
sealed trait Match {

  /** The seating the first round of the match was dealt with. */
  def opening: Seats

  /** Every round played, most recent first, as the discard pile keeps its cards. */
  def rounds: List[RoundScore]

  def totals: Tally = rounds.foldLeft(Tally.nothing) { (totals, round) =>
    round.scored.fold(totals)((winner, points) => totals.plus(winner, points))
  }

  def roundsWon: Tally = rounds.foldLeft(Tally.nothing) { (won, round) =>
    round.scored.fold(won)((winner, _) => won.plus(winner, 1))
  }
}

object Match {

  /** The score a player has to reach to win the match. */
  val Target: Int = 100

  /** What winning the match is worth on top of the points that won it. */
  val GameBonus: Int = 100

  /** What each round a player won is worth once the match is over. */
  val BoxBonus: Int = 25

  def start(dealer: Player): InProgress = new InProgress(Seats(dealer), Nil)

  /** A match with rounds still to play. The constructor is private, so the only ways to reach one
    * are [[start]] and [[InProgress.played]], and a match that a player has won cannot be one.
    */
  final case class InProgress private[Match] (opening: Seats, rounds: List[RoundScore])
      extends Match {

    /** Who deals the next round. The deal passes after every round that scored, and a dead round
      * is dealt again by the same dealer, so this counts the rounds rather than reading a field
      * that somebody has to remember to change.
      */
    def seats: Seats =
      if (rounds.count(_.scored.isDefined) % 2 === 0) opening else opening.passed

    /** This match with one more round played, finished if that round won it.
      *
      * Only one player scores in a round, so only one total can cross the target and there is
      * never a choice of winner to make.
      */
    def played(score: RoundScore): Match = {
      val next = new InProgress(opening, score :: rounds)

      if (Player.values.exists(player => next.totals(player) >= Target))
        new Finished(opening, next.rounds)
      else next
    }
  }

  /** A match a player has won. Private for the same reason: a finished match can only come from a
    * total crossing the target.
    */
  final case class Finished private[Match] (opening: Seats, rounds: List[RoundScore])
      extends Match {

    /** Who won and what each player finished on.
      *
      * The winner adds the game bonus and both players add a box for each round they won. A
      * winner who left the other player on nothing doubles their own figure, and that never has
      * to argue with a box the loser earned, because winning a round is always worth at least a
      * point and so a player on nothing is a player who won nothing.
      */
    def result: MatchResult = {
      val standing = totals
      val boxes    = roundsWon

      /* The round that ended the match put one player at the target and left the other below it,
       * so the player with the most points is the winner and there is no tie to break. */
      val winner = if (standing(Player.One) >= standing(Player.Two)) Player.One else Player.Two
      val loser  = winner.other

      val won    = standing(winner) + GameBonus + BoxBonus * boxes(winner)
      val lost   = standing(loser) + BoxBonus * boxes(loser)
      val figure = if (standing(loser) === 0) won * 2 else won

      MatchResult(winner, Tally.nothing.plus(winner, figure).plus(loser, lost))
    }
  }

  given Eq[Match] = Eq.fromUniversalEquals

  given Show[Match] = Show.fromToString
}
