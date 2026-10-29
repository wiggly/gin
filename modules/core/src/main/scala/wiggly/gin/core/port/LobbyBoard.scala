package wiggly.gin.core.port

import fs2.Stream
import wiggly.gin.core.domain.{Listing, Lobby}

/** Where the lobby is kept.
  *
  * `change` takes a change for the reason `GameRepository.update` does: two streams that open
  * together queue rather than one overwriting the other. `watch` starts with the listings as they
  * stand and then sends each change, so there is no window between asking and listening.
  */
trait LobbyBoard[F[_]] {

  def change(f: Lobby => Lobby): F[Unit]

  def watch: Stream[F, List[Listing]]
}
