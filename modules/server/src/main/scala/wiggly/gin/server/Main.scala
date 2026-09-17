package wiggly.gin.server

import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.HttpRoutes
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import wiggly.gin.server.adapter.http.GinApi
import wiggly.gin.server.config.AppConfig

object Main extends IOApp {

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  /** The game's own endpoints, which arrive once `core` exposes a service to drive. Until then the
    * server still starts, serves /health and answers anything else with a well-formed 404.
    */
  private val gameRoutes: HttpRoutes[IO] = HttpRoutes.empty[IO]

  def run(args: List[String]): IO[ExitCode] =
    IO.fromEither(AppConfig.fromEnv)
      .flatMap { config =>
        HttpServer
          .resource[IO](config.http, GinApi.httpApp[IO](gameRoutes))
          // The server runs until the process is asked to stop; IOApp translates
          // SIGTERM into cancellation, which releases the resource and drains.
          .useForever
      }
      .as(ExitCode.Success)
}
