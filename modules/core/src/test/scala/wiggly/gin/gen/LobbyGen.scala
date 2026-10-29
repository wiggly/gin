package wiggly.gin.gen

import cats.Show
import org.scalacheck.Gen
import wiggly.gin.core.domain.{GameId, Lobby, ListedAt}

/** Runs of lobby transitions over three games. Three, so that a run touches each one often enough
  * to open, close and reopen it.
  */
object LobbyGen {

  enum Step {
    case Open(id: GameId, at: ListedAt)
    case Close(id: GameId)
    case Take(id: GameId)
  }

  object Step {
    given Show[Step] = Show.fromToString
  }

  val ids: List[GameId] = List("g1", "g2", "g3").map(GameId.apply)

  val step: Gen[Step] = Gen.oneOf(ids).flatMap { id =>
    Gen.frequency(
      3 -> Gen.choose(0L, 1000000L).map(at => Step.Open(id, ListedAt(at))),
      2 -> Gen.const(Step.Close(id)),
      1 -> Gen.const(Step.Take(id))
    )
  }

  val steps: Gen[List[Step]] = Gen.choose(0, 30).flatMap(Gen.listOfN(_, step))

  def applied(steps: List[Step]): Lobby =
    steps.foldLeft(Lobby.empty) { (lobby, step) =>
      step match {
        case Step.Open(id, at) => lobby.opened(id, NameGen.named("Ada"), at)
        case Step.Close(id)    => lobby.closed(id)
        case Step.Take(id)     => lobby.taken(id)
      }
    }
}
