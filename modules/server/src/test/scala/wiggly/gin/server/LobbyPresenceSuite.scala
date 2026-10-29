package wiggly.gin.server

import cats.effect.{Deferred, FiberIO, IO}
import cats.effect.testkit.TestControl
import cats.implicits.*
import fs2.Stream
import weaver.{Expectations, SimpleIOSuite}
import wiggly.gin.core.domain.*
import wiggly.gin.core.port.{Credentials, GameRepository, GameService, LobbyBoard}
import wiggly.gin.core.service.Games
import wiggly.gin.fake.{CountingSecrets, FixedShuffler}
import wiggly.gin.gen.NameGen
import wiggly.gin.server.adapter.memory.{MemoryGameEvents, MemoryGameRepository, MemoryLobbyBoard}

import scala.concurrent.duration.*

object LobbyPresenceSuite extends SimpleIOSuite {

  private val ada   = NameGen.named("Ada")
  private val grace = NameGen.named("Grace")

  private final case class Table(
      service: GameService[IO],
      repository: GameRepository[IO],
      board: LobbyBoard[IO]
  )

  private val table: IO[Table] = for {
    repository <- MemoryGameRepository[IO]
    events     <- MemoryGameEvents[IO]
    board      <- MemoryLobbyBoard[IO]
    secrets    <- CountingSecrets[IO]
    service = Games[IO](repository, events, board, new FixedShuffler[IO](Deck.ordered), secrets)
  } yield Table(service, repository, board)

  private def watching(table: Table, seat: Credentials): IO[Stream[IO, PlayerView]] =
    table.service.watch(seat.id, seat.token).flatMap {
      case Right(views) => IO.pure(views)
      case Left(fault)  => IO.raiseError(new IllegalStateException(s"refused: $fault"))
    }

  private def now(table: Table): IO[List[Listing]] = table.board.watch.take(1).compile.lastOrError

  /** Waits for the lobby to read as asked, so that a test never races the fiber that changes it. */
  private def until(table: Table)(wanted: List[Listing] => Boolean): IO[List[Listing]] =
    table.board.watch.find(wanted).compile.lastOrError.timeout(5.seconds)

  /** The lobby as the stream's first view arrives. The tap that takes a row down runs before the
    * view does, so this reads the lobby after the stream decided about it.
    */
  private def onFirstView(table: Table, views: Stream[IO, PlayerView]): IO[List[Listing]] =
    views.evalMap(_ => now(table)).take(1).compile.lastOrError.timeout(5.seconds)

  /** Starts the host's stream and returns once its first view has arrived, by which time the stream
    * has made its row.
    */
  private def watchingSince(table: Table, seat: Credentials): IO[FiberIO[Unit]] = for {
    views   <- watching(table, seat)
    arrived <- Deferred[IO, Unit]
    fiber   <- views.evalTap(_ => arrived.complete(())).compile.drain.start
    _       <- arrived.get.timeout(5.seconds)
  } yield fiber

  /** The one rule that spans both stores: every listed game is one the store holds as waiting. */
  private def agrees(table: Table): IO[Expectations] = for {
    listings <- now(table)
    stored   <- listings.traverse(listing => table.repository.read(listing.id))
  } yield expect(stored.forall {
    case Some(_: Game.AwaitingOpponent) => true
    case _                              => false
  })

  test("a host who watches lists the game, and the row goes when the stream does") {
    for {
      table  <- table
      host   <- table.service.create(ada)
      views  <- watching(table, host)
      fiber  <- views.compile.drain.start
      listed <- until(table)(_.nonEmpty)
      during <- agrees(table)
      _      <- fiber.cancel
      after  <- now(table)
    } yield expect.eql(listed.map(l => (l.id, l.host)), List((host.id, ada))) and
      during and
      expect.eql(after, List.empty[Listing])
  }

  test("two streams from the host keep the row until both are gone") {
    for {
      table  <- table
      host   <- table.service.create(ada)
      first  <- watchingSince(table, host)
      second <- watchingSince(table, host)
      _      <- first.cancel
      one    <- now(table)
      _      <- second.cancel
      none   <- now(table)
    } yield expect.eql(one.map(_.id), List(host.id)) and expect.eql(none, List.empty[Listing])
  }

  test("a join takes the row down while the host is still watching") {
    for {
      table <- table
      host  <- table.service.create(ada)
      fiber <- watching(table, host).flatMap(_.compile.drain.start)
      _     <- until(table)(_.nonEmpty)
      _     <- table.service.join(host.id, grace)
      after <- until(table)(_.isEmpty)
      check <- agrees(table)
      _     <- fiber.cancel
    } yield expect.eql(after, List.empty[Listing]) and check
  }

  test("a join that lands between the host's look and the listing leaves no row behind") {
    for {
      table <- table
      host  <- table.service.create(ada)
      views <- watching(table, host)
      _     <- table.service.join(host.id, grace)
      seen  <- onFirstView(table, views)
    } yield expect.eql(seen, List.empty[Listing])
  }

  test("a guest's stream lists nothing") {
    for {
      table <- table
      host  <- table.service.create(ada)
      guest <- table.service
        .join(host.id, grace)
        .flatMap(joined => IO.fromOption(joined.toOption)(new IllegalStateException("refused")))
      views <- watching(table, guest)
      seen  <- onFirstView(table, views)
    } yield expect.eql(seen, List.empty[Listing])
  }

  test("a row carries the time the host's stream opened") {
    TestControl.executeEmbed {
      for {
        table <- table
        host  <- table.service.create(ada)
        _     <- IO.sleep(5.minutes)
        views <- watching(table, host)
        seen  <- views.evalMap(_ => now(table)).take(1).compile.lastOrError
      } yield expect.eql(seen.map(_.since), List(ListedAt(5.minutes.toMillis)))
    }
  }
}
