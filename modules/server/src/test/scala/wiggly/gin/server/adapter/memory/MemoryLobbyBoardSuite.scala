package wiggly.gin.server.adapter.memory

import cats.effect.{Deferred, IO}
import cats.implicits.*
import fs2.Stream
import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{GameId, ListedAt, Listing}
import wiggly.gin.gen.NameGen

import scala.concurrent.duration.*

object MemoryLobbyBoardSuite extends SimpleIOSuite {

  private val ada     = NameGen.named("Ada")
  private val id      = GameId("g1")
  private val listing = Listing(id, ada, ListedAt(1))

  test("a watcher is handed the lobby as it stands before anything else happens") {
    for {
      board <- MemoryLobbyBoard[IO]
      _     <- board.change(_.opened(id, ada, ListedAt(1)))
      seen  <- board.watch.take(1).compile.toList
    } yield expect.eql(seen, List(List(listing)))
  }

  /** A second tab on a game already listed changes the count and not the listings, so the watcher
    * is sent nothing. Without the duplicate check the same list would arrive a second time, and
    * the interruption would not cut it short.
    */
  test("a watcher is not sent a list that a change left as it was") {
    for {
      board <- MemoryLobbyBoard[IO]
      _     <- board.change(_.opened(id, ada, ListedAt(1)))
      seen  <- board.watch.zipWithIndex
        .evalMap { (listings, index) =>
          IO.whenA(index === 0L)(board.change(_.opened(id, ada, ListedAt(2)))).as(listings)
        }
        .interruptAfter(200.millis)
        .compile
        .toList
    } yield expect.eql(seen, List(List(listing)))
  }

  /** The watcher is listening before either change is made, so the take is heard after the
    * repeat that left the listings alone.
    */
  test("a watcher hears a real change that follows a repeat which left the listings alone") {
    for {
      board <- MemoryLobbyBoard[IO]
      _     <- board.change(_.opened(id, ada, ListedAt(1)))
      heard <- Deferred[IO, Unit]
      seen  <- board.watch
        .evalTap(_ => heard.complete(()).void)
        .take(2)
        .concurrently(
          Stream.exec(
            heard.get >> board.change(_.opened(id, ada, ListedAt(2))) >> board.change(_.taken(id))
          )
        )
        .compile
        .toList
    } yield expect.eql(seen, List(List(listing), List.empty[Listing]))
  }

  test("changes made at once are all kept") {
    for {
      board <- MemoryLobbyBoard[IO]
      _     <- (1 to 100).toList.parTraverse_ { n =>
        board.change(_.opened(GameId(s"g$n"), ada, ListedAt(n.toLong)))
      }
      seen <- board.watch.take(1).compile.lastOrError
    } yield expect.eql(seen.size, 100)
  }
}
