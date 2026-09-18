package wiggly.gin.core.domain

import cats.syntax.all.*
import cats.{Eq, Show}

/** What the end of a round revealed, and what it came to.
  *
  * Those are the same thing. A knock puts both hands on the table, and the count of them is what
  * decides the points, so the working and what became public are one value. A round nobody knocked
  * reveals nothing and is worth nothing, and both arrangements are absent to say so.
  */
final case class RoundResult(
    outcome: Outcome,
    knocker: Option[Arrangement],
    defender: Option[Defence],
    score: RoundScore
)

object RoundResult {

  /** The count of a round that is over.
    *
    * Gin is the same arithmetic as a knock with one difference, which is that the other player has
    * nothing to lay off onto. Passing no melds to [[Defence.against]] says exactly that, so the
    * rule that gin blocks layoffs is one expression here rather than a branch that skips the
    * layoffs and another that does them.
    */
  def of(round: GameState.Finished): RoundResult = {
    round.outcome match {
      case Outcome.Dead => RoundResult(Outcome.Dead, None, None, RoundScore.Dead)
      case knocked @ Outcome.Knocked(knocker) => {
        val arrangement = round.table.hands(knocker).arrangement
        val gin         = arrangement.deadwoodValue === 0
        val laid        = if (gin) Nil else arrangement.melds
        val defence     = Defence.against(round.table.hands(knocker.other), laid)

        RoundResult(
          knocked,
          Some(arrangement),
          Some(defence),
          scored(knocker, arrangement, defence, gin)
        )
      }
    }
  }

  private def scored(
      knocker: Player,
      arrangement: Arrangement,
      defence: Defence,
      gin: Boolean
  ): RoundScore = {
    val kept = arrangement.deadwoodValue
    val left = defence.deadwoodValue

    if (gin) RoundScore.Gin(knocker, left + RoundScore.GinBonus)
    else if (left <= kept)
      RoundScore.Undercut(knocker.other, kept - left + RoundScore.UndercutBonus)
    else RoundScore.Knock(knocker, left - kept)
  }

  given Eq[RoundResult] = Eq.fromUniversalEquals

  given Show[RoundResult] = Show.fromToString
}
