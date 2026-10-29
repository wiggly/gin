package wiggly.gin.core.domain

import cats.implicits.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.gen.LobbyGen.Step
import wiggly.gin.gen.{LobbyGen, NameGen}

object LobbySuite extends SimpleIOSuite with Checkers {

  private val ada = NameGen.named("Ada")

  /** The same lobby kept the long way, as a count and a time per game. The count goes up for an
    * open, down for a close but never below zero, and to zero for a take. The time is the one
    * from the open that took the count off zero.
    */
  private def expected(steps: List[Step]): List[Listing] = {
    val rows = steps.foldLeft(Map.empty[GameId, (Int, ListedAt)]) { (held, step) =>
      step match {
        case Step.Open(id, at) =>
          held.updated(id, held.get(id).fold((1, at))((count, since) => (count + 1, since)))
        case Step.Close(id) =>
          held.get(id) match {
            case Some((count, since)) if count > 1 => held.updated(id, (count - 1, since))
            case _                                 => held.removed(id)
          }
        case Step.Take(id) => held.removed(id)
      }
    }

    rows.toList
      .map { case (id, (_, since)) => Listing(id, ada, since) }
      .sortBy(listing => (listing.since.millis, listing.id.value))
  }

  test("a game is listed while its opens outweigh its closes, from the open that listed it") {
    forall(LobbyGen.steps) { steps =>
      expect.eql(LobbyGen.applied(steps).listings, expected(steps))
    }
  }

  test("the listings are the oldest first") {
    forall(LobbyGen.steps) { steps =>
      val times = LobbyGen.applied(steps).listings.map(_.since.millis)

      expect.eql(times, times.sorted)
    }
  }

  test("closing or taking a game the lobby does not hold changes nothing") {
    forall(LobbyGen.steps) { steps =>
      val lobby  = LobbyGen.applied(steps)
      val absent = GameId("absent")

      expect.eql(lobby.closed(absent), lobby) and expect.eql(lobby.taken(absent), lobby)
    }
  }

  pureTest("a second tab keeps the game listed when the first one closes") {
    val id    = GameId("g1")
    val twice = Lobby.empty.opened(id, ada, ListedAt(1)).opened(id, ada, ListedAt(2))

    expect.eql(twice.closed(id).listings, List(Listing(id, ada, ListedAt(1)))) and
      expect.eql(twice.closed(id).closed(id).listings, List.empty[Listing])
  }

  pureTest("a take removes the game however many streams hold it") {
    val id    = GameId("g1")
    val twice = Lobby.empty.opened(id, ada, ListedAt(1)).opened(id, ada, ListedAt(2))

    expect.eql(twice.taken(id).listings, List.empty[Listing])
  }
}
