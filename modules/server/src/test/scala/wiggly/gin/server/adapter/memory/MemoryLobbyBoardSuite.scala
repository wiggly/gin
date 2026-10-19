package wiggly.gin.server.adapter.memory

import cats.effect.IO
import cats.implicits.*
import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{GameId, ListedAt, Listing}
import wiggly.gin.gen.NameGen

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

  /** The take happens after the first lobby arrived, which proves that the watcher was already
    * listening. The second open in between leaves the listings as they were, so it sends nothing.
    */
  test("a watcher hears each change to the listings and nothing that leaves them alone") {
    for {
      board <- MemoryLobbyBoard[IO]
      _     <- board.change(_.opened(id, ada, ListedAt(1)))
      seen  <- board.watch
        .take(2)
        .zipWithIndex
        .evalMap { (listings, index) =>
          IO.whenA(index === 0L) {
            board.change(_.opened(id, ada, ListedAt(2))) >> board.change(_.taken(id))
          }.as(listings)
        }
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
