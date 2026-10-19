package wiggly.gin.server.adapter.memory

import cats.effect.Concurrent
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import wiggly.gin.core.domain.{Listing, Lobby}
import wiggly.gin.core.port.LobbyBoard

/** The lobby in one signal, for the reasons `MemoryGameEvents` uses one per game: a watcher that
  * falls behind wants the list as it now is, and nobody waits for a slow one.
  *
  * `changes` drops a lobby whose listings did not move, such as a second tab opening on a game
  * already listed, so that a watcher is not sent the same list twice.
  */
object MemoryLobbyBoard {

  def apply[F[_]: Concurrent]: F[LobbyBoard[F]] =
    SignallingRef.of[F, Lobby](Lobby.empty).map(from)

  private def from[F[_]](lobby: SignallingRef[F, Lobby]): LobbyBoard[F] = {
    new LobbyBoard[F] {

      def change(f: Lobby => Lobby): F[Unit] = lobby.update(f)

      def watch: Stream[F, List[Listing]] = lobby.discrete.map(_.listings).changes
    }
  }
}
