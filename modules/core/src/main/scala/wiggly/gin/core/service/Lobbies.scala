package wiggly.gin.core.service

import fs2.Stream
import wiggly.gin.core.domain.Listing
import wiggly.gin.core.port.{LobbyBoard, LobbyService}

object Lobbies {

  def apply[F[_]](board: LobbyBoard[F]): LobbyService[F] = {
    new LobbyService[F] {

      def listings: Stream[F, List[Listing]] = board.watch
    }
  }
}
