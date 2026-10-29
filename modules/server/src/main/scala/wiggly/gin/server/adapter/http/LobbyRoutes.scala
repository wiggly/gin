package wiggly.gin.server.adapter.http

import cats.effect.Temporal
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import wiggly.gin.core.port.LobbyService

import scala.concurrent.duration.FiniteDuration

import wiggly.gin.protocol.Codecs.given

/** The lobby, which anybody can watch. It names games and hosts and nothing that claims a seat,
  * so it asks for no token.
  */
object LobbyRoutes {

  def apply[F[_]: Temporal](lobby: LobbyService[F], heartbeat: FiniteDuration): HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl.*

    HttpRoutes.of[F] { case GET -> Root / "lobby" / "events" =>
      Ok(EventStreams(lobby.listings, heartbeat))
    }
  }
}
