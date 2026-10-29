package wiggly.gin.core.port

import fs2.Stream
import wiggly.gin.core.domain.Listing

/** The lobby as a client reads it.
  *
  * Separate from [[GameService]], because every call there is about one game and takes a token,
  * and the lobby is about every game and takes none.
  */
trait LobbyService[F[_]] {

  def listings: Stream[F, List[Listing]]
}
