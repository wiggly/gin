package wiggly.gin.core.domain

import cats.{Eq, Show}

/** A game as one player is allowed to see it.
  *
  * The redaction is the shape rather than a filter. [[PlayerView.InPlay]] has no field that could
  * hold the other player's cards or the order of the stock, so leaking one takes a new field
  * rather than a forgotten guard, and nothing downstream has to remember to strip anything.
  */
enum PlayerView {

  case AwaitingOpponent(id: GameId, you: Player)

  /** @param arrangement
    *   the requester's own cards, arranged as well as the rules allow, which is also what tells
    *   them whether they can knock.
    * @param onTurn
    *   carried rather than left to the client, because `AwaitingOpeningDraw` names nobody and
    *   resolving it needs the seating.
    * @param phase
    *   unredacted, which is safe on inspection: the only card it names is the one its subject took
    *   from the pile, in the open.
    * @param previous
    *   the round before this one, which is public because a knock puts both hands on the table.
    */
  case InPlay(
      id: GameId,
      you: Player,
      arrangement: Arrangement,
      dealer: Player,
      onTurn: Player,
      phase: Phase,
      upcard: Option[Card],
      stockSize: Int,
      discardSize: Int,
      opponentSize: Int,
      totals: Tally,
      previous: Option[RoundResult]
  )

  case Over(id: GameId, you: Player, result: MatchResult, previous: RoundResult)
}

object PlayerView {

  /** This game as this player may see it. The player is resolved from a token before this is
    * called, so there is no seat here that the caller has not already proved.
    */
  def of(game: Game, you: Player): PlayerView = {
    game match {
      case waiting: Game.AwaitingOpponent => AwaitingOpponent(waiting.id, you)
      case playing: Game.InPlay           => {
        val table = playing.round.table

        InPlay(
          id = playing.id,
          you = you,
          arrangement = table.hands(you).arrangement,
          dealer = playing.round.seats.dealer,
          onTurn = playing.round.onTurn,
          phase = playing.round.phase,
          upcard = table.discard.headOption,
          stockSize = table.stock.size,
          discardSize = table.discard.size,
          opponentSize = table.hands(you.other).size,
          totals = playing.ledger.totals,
          previous = playing.previous
        )
      }
      case over: Game.Over => Over(over.id, you, over.ledger.result, over.previous)
    }
  }

  given Eq[PlayerView] = Eq.fromUniversalEquals

  given Show[PlayerView] = Show.fromToString
}
