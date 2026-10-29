package wiggly.gin.server.adapter.http

import cats.effect.IO
import cats.implicits.*
import org.http4s.implicits.uri
import org.http4s.{Method, Request, Status}
import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{GameId, ListedAt}
import wiggly.gin.core.service.Lobbies
import wiggly.gin.gen.NameGen
import wiggly.gin.server.adapter.memory.MemoryLobbyBoard

import scala.concurrent.duration.*

object LobbyRoutesSuite extends SimpleIOSuite {

  test("the lobby stream opens with the games waiting, and needs no token") {
    for {
      board <- MemoryLobbyBoard[IO]
      _     <- board.change(_.opened(GameId("g1"), NameGen.named("Ada"), ListedAt(0)))
      app = LobbyRoutes[IO](Lobbies[IO](board), 15.seconds).orNotFound
      response <- app.run(Request[IO](Method.GET, uri"/lobby/events"))
      opening  <- response.body
        .through(fs2.text.utf8.decode)
        .take(1)
        .compile
        .string
        .timeout(5.seconds)
    } yield expect.eql(response.status, Status.Ok) and
      expect(response.contentType.exists(_.mediaType.subType === "event-stream")) and
      expect(opening.contains("\"id\":\"g1\"")) and
      expect(opening.contains("\"host\":\"Ada\""))
  }

  test("an idle lobby stream sends a heartbeat") {
    for {
      board <- MemoryLobbyBoard[IO]
      app = LobbyRoutes[IO](Lobbies[IO](board), 50.millis).orNotFound
      response <- app.run(Request[IO](Method.GET, uri"/lobby/events"))
      beat     <- response.body
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .find(_.startsWith(":"))
        .compile
        .lastOrError
        .timeout(5.seconds)
    } yield expect(beat.contains("still here"))
  }
}
