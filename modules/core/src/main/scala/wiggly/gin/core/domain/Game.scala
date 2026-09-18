package wiggly.gin.core.domain

import cats.syntax.all.*
import cats.{Eq, Show}

final case class GameId(value: String)

object GameId {
  given Eq[GameId] = Eq.fromUniversalEquals

  given Show[GameId] = Show.fromToString
}

/** What a player shows to claim a seat. Opaque: the rules never look inside one, and how an
  * unguessable one is made belongs to the adapter that mints it.
  */
final case class Token(value: String)

object Token {
  given Eq[Token] = Eq.fromUniversalEquals

  given Show[Token] = Show.fromToString
}

/** A token for each player, keyed so that a lookup cannot fail. */
final case class Tokens(one: Token, two: Token) {

  def apply(player: Player): Token = {
    player match {
      case Player.One => one
      case Player.Two => two
    }
  }

  def holder(token: Token): Option[Player] =
    Player.values.find(player => apply(player) === token)
}

object Tokens {
  given Eq[Tokens] = Eq.fromUniversalEquals

  given Show[Tokens] = Show.fromToString
}

/** A game as it is stored: who is playing, what the match stands at, and the round in play.
  *
  * A stored game never holds a finished round. The moment a round ends it is scored, folded into
  * the ledger and followed by a fresh deal, or the match is over and there is no round left to
  * hold. That is why [[Game.InPlay]] names a `GameState.InProgress` rather than a `GameState`, and
  * it is what leaves every caller past the guard with a round it can actually move.
  */
sealed trait Game {

  def id: GameId

  /** Which player this token seats, or nobody. */
  def holder(token: Token): Option[Player] = {
    this match {
      case Game.AwaitingOpponent(_, host) => Option.when(host === token)(Player.One)
      case playing: Game.InPlay           => playing.tokens.holder(token)
      case over: Game.Over                => over.tokens.holder(token)
    }
  }

  /** This game with a second player seated and the first round dealt.
    *
    * The player who joins deals, so the one who was waiting is offered the upcard first. The deal
    * alternates from the round after, so the advantage is a courtesy rather than a rule.
    */
  def joined(guest: Token, deck: Deck): Either[GameFault, Game] = {
    this match {
      case Game.AwaitingOpponent(id, host) => {
        val ledger = Match.start(Player.Two)

        Right(
          Game.InPlay(
            id,
            Tokens(host, guest),
            ledger,
            GameState.deal(deck, ledger.seats),
            None
          )
        )
      }
      case _ => Left(GameFault.AlreadyFull)
    }
  }

  /** This game with the move played, or why it was refused.
    *
    * `next` is the deck for the round this move might set off. It arrives with the move because
    * the store applies a change that cannot reach for one, and a deck that turns out not to be
    * needed is simply dropped.
    */
  def played(player: Player, move: Move, next: Deck): Either[GameFault, Game] = {
    this match {
      case playing: Game.InPlay =>
        GameState(playing.round, player, move).left.map(GameFault.Illegal.apply).map {
          case round: GameState.InProgress => playing.copy(round = round)
          case round: GameState.Finished   => Game.settled(playing, round, next)
        }
      case _ => Left(GameFault.NotInPlay)
    }
  }
}

object Game {

  final case class AwaitingOpponent(id: GameId, host: Token) extends Game

  final case class InPlay(
      id: GameId,
      tokens: Tokens,
      ledger: Match.InProgress,
      round: GameState.InProgress,
      previous: Option[RoundResult]
  ) extends Game

  final case class Over(
      id: GameId,
      tokens: Tokens,
      ledger: Match.Finished,
      previous: RoundResult
  ) extends Game

  /** The round is counted, the ledger takes the score, and either the next round is dealt or the
    * match is over. The round that just ended stays in the game as `previous`, because a player
    * has to be able to see the hands their score came from.
    */
  private def settled(playing: InPlay, round: GameState.Finished, next: Deck): Game = {
    val result = RoundResult.of(round)

    playing.ledger.played(result.score) match {
      case ledger: Match.InProgress =>
        InPlay(playing.id, playing.tokens, ledger, GameState.deal(next, ledger.seats), Some(result))
      case ledger: Match.Finished =>
        Over(playing.id, playing.tokens, ledger, result)
    }
  }

  given Eq[Game] = Eq.fromUniversalEquals

  given Show[Game] = Show.fromToString
}
