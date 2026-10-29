package wiggly.gin.server

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.semigroupk.*
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory
import wiggly.gin.core.port.{GameService, LobbyService}
import wiggly.gin.core.service.{Games, Lobbies}
import wiggly.gin.server.adapter.http.{GameRoutes, GinApi, LobbyRoutes}
import wiggly.gin.server.adapter.memory.{MemoryGameEvents, MemoryGameRepository, MemoryLobbyBoard}
import wiggly.gin.server.adapter.random.{RandomSecrets, RandomShuffler}
import wiggly.gin.server.config.AppConfig

/** The composition root: the only place that knows every adapter.
  *
  * Reading the configuration, choosing the adapters and running the server is all that happens
  * here. Nothing in this file decides anything about gin rummy.
  */
object Main extends IOApp {

  private given LoggerFactory[IO] = Slf4jFactory.create[IO]

  /** The store, the broker and the lobby board are held in memory, so a game lasts as long as the
    * process does. They are the three adapters a persistent store would replace, and they are
    * chosen here rather than anywhere the game can see.
    */
  private val services: IO[(GameService[IO], LobbyService[IO])] = for {
    repository <- MemoryGameRepository[IO]
    events     <- MemoryGameEvents[IO]
    board      <- MemoryLobbyBoard[IO]
    shuffler   <- RandomShuffler[IO]
    secrets    <- RandomSecrets[IO]
  } yield (Games[IO](repository, events, board, shuffler, secrets), Lobbies[IO](board))

  def run(args: List[String]): IO[ExitCode] =
    IO.fromEither(AppConfig.fromEnv)
      .flatMap { config =>
        services.flatMap { (service, lobby) =>
          HttpServer
            .resource[IO](
              config.http,
              GinApi.httpApp[IO](
                GameRoutes[IO](service, config.http.eventHeartbeat) <+>
                  LobbyRoutes[IO](lobby, config.http.eventHeartbeat)
              )
            )
            // The server runs until the process is asked to stop; IOApp translates
            // SIGTERM into cancellation, which releases the resource and drains.
            .useForever
        }
      }
      .as(ExitCode.Success)
}
