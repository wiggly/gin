package wiggly.gin.server.adapter.memory

import cats.effect.IO
import cats.implicits.*
import weaver.SimpleIOSuite
import wiggly.gin.core.domain.*

object MemoryGameRepositorySuite extends SimpleIOSuite {

  private val id      = GameId("a-game")
  private val host    = Token("host")
  private val waiting = Game.AwaitingOpponent(id, host)

  test("a game that was stored can be read back") {
    for {
      store <- MemoryGameRepository[IO]
      _     <- store.create(waiting)
      read  <- store.read(id)
    } yield expect.eql(read, Some(waiting))
  }

  test("an id the store does not hold is nothing to read and no such game to change") {
    for {
      store   <- MemoryGameRepository[IO]
      read    <- store.read(id)
      changed <- store.update(id)(Right(_))
    } yield expect.eql(read, None) and expect.eql(changed, Left(GameFault.NoSuchGame))
  }

  test("an update writes the change and hands back what it wrote") {
    for {
      store   <- MemoryGameRepository[IO]
      _       <- store.create(waiting)
      changed <- store.update(id)(_.joined(Token("guest"), Deck.ordered))
      read    <- store.read(id)
    } yield expect.eql(changed.map(_.id), Right(id)) and
      expect.eql(read, changed.toOption) and
      expect(read.exists(_.isInstanceOf[Game.InPlay]))
  }

  test("a change that is refused leaves the stored game exactly as it was") {
    for {
      store   <- MemoryGameRepository[IO]
      _       <- store.create(waiting)
      changed <- store.update(id)(_ => Left(GameFault.NotInPlay))
      read    <- store.read(id)
    } yield expect.eql(changed, Left(GameFault.NotInPlay)) and expect.eql(read, Some(waiting))
  }

  /** The property the whole port exists for. A store that read and then wrote would seat several
    * of these, because every one of them starts from the same waiting game.
    */
  test("a hundred players racing for the second seat leave exactly one seated") {
    for {
      store <- MemoryGameRepository[IO]
      _     <- store.create(waiting)
      races <- (1 to 100).toList.parTraverse { attempt =>
        store.update(id)(_.joined(Token(s"guest-$attempt"), Deck.ordered))
      }
      read <- store.read(id)
    } yield expect.eql(races.count(_.isRight), 1) and
      expect.eql(races.count(_ === Left(GameFault.AlreadyFull)), 99) and
      expect(read.exists(_.isInstanceOf[Game.InPlay]))
  }
}
