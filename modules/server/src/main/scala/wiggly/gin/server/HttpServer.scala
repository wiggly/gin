package wiggly.gin.server

import cats.effect.{Async, Resource}
import fs2.io.net.Network
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.http4s.server.middleware.Logger
import org.typelevel.log4cats.LoggerFactory
import wiggly.gin.server.config.HttpConfig

/** The HTTP listener, as a resource: acquiring it binds the port, releasing it stops accepting
  * connections and drains the ones still in flight.
  */
object HttpServer {

  def resource[F[_]: Async: Network: LoggerFactory](
      config: HttpConfig,
      httpApp: HttpApp[F]
  ): Resource[F, Server] = {
    val logger = LoggerFactory[F].getLogger

    EmberServerBuilder
      .default[F]
      .withHost(config.host)
      .withPort(config.port)
      .withShutdownTimeout(config.shutdownTimeout)
      // Headers are logged but bodies are not: a body may carry another player's hand.
      .withHttpApp(Logger.httpApp(logHeaders = true, logBody = false)(httpApp))
      .build
      .evalTap(server => logger.info(s"listening on ${server.baseUri}"))
  }
}
