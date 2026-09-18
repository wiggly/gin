package wiggly.gin.core.port

import cats.{Eq, Show}
import fs2.Stream
import wiggly.gin.core.domain.{GameFault, GameId, Move, Player, PlayerView, Token}

/** What a seat at a game is worth: the id of the game and the token that claims the seat. */
final case class Credentials(id: GameId, you: Player, token: Token)

object Credentials {
  given Eq[Credentials] = Eq.fromUniversalEquals

  given Show[Credentials] = Show.fromToString
}

/** Everything a client can do, and the only way in.
  *
  * Every call but `create` and `join` takes the caller's token, because everything else it could
  * be told is something one player is allowed to see and the other is not. What comes back is
  * always a [[PlayerView]], which is the only shape a game leaves the application in.
  */
trait GameService[F[_]] {

  def create: F[Credentials]

  def join(id: GameId): F[Either[GameFault, Credentials]]

  def look(id: GameId, token: Token): F[Either[GameFault, PlayerView]]

  /** The move played, answered with the mover's own view of what they did, so that a client needs
    * no second request to see the result of its move.
    */
  def play(id: GameId, token: Token, move: Move): F[Either[GameFault, PlayerView]]

  def watch(id: GameId, token: Token): F[Either[GameFault, Stream[F, PlayerView]]]
}
