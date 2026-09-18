package wiggly.gin.server.adapter.memory

import cats.effect.IO
import cats.implicits.*
import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{Game, GameId, Token}

import scala.concurrent.duration.*

object MemoryGameEventsSuite extends SimpleIOSuite {

  private val id    = GameId("a-game")
  private val other = GameId("another-game")

  private def waiting(gameId: GameId, host: String): Game =
    Game.AwaitingOpponent(gameId, Token(host))

  test("a watcher is handed the game as it stands before anything else happens") {
    for {
      events <- MemoryGameEvents[IO]
      _      <- events.publish(waiting(id, "host"))
      seen   <- events.watch(id).take(1).compile.toList
    } yield expect.eql(seen, List(waiting(id, "host")))
  }

  /** The publish happens after the first state has arrived, which is what proves the watcher was
    * already listening rather than that the timing happened to work out.
    */
  test("a watcher hears a game published after it started listening") {
    for {
      events <- MemoryGameEvents[IO]
      _      <- events.publish(waiting(id, "host"))
      seen   <- events
        .watch(id)
        .take(2)
        .zipWithIndex
        .evalMap { (game, index) =>
          IO.whenA(index === 0L)(events.publish(waiting(id, "second"))).as(game)
        }
        .compile
        .toList
    } yield expect.eql(seen, List(waiting(id, "host"), waiting(id, "second")))
  }

  test("a watcher hears nothing about a game it did not ask for") {
    for {
      events <- MemoryGameEvents[IO]
      _      <- events.publish(waiting(other, "host"))
      seen   <- events.watch(id).interruptAfter(200.millis).compile.toList
    } yield expect.eql(seen, List.empty[Game])
  }

  test("two watchers of one game are both told") {
    for {
      events <- MemoryGameEvents[IO]
      _      <- events.publish(waiting(id, "host"))
      seen   <- List(id, id).parTraverse(each => events.watch(each).take(1).compile.toList)
    } yield expect.eql(seen, List(List(waiting(id, "host")), List(waiting(id, "host"))))
  }
}
