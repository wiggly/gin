package wiggly.gin.ui

import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.ListedAt

object WaitingSuite extends SimpleIOSuite with Checkers {

  pureTest("a game listed a moment ago has waited under a minute") {
    expect.same("waiting under a minute", Waiting.age(ListedAt(0), 59999))
  }

  pureTest("whole minutes are counted down, not rounded") {
    expect
      .same("waiting 1 min", Waiting.age(ListedAt(0), 60000))
      .and(
        expect.same("waiting 3 min", Waiting.age(ListedAt(0), 239999))
      )
  }

  /** The time comes from the server and the clock from the browser, and the two can disagree. */
  test("a listing from the future has waited under a minute, not a negative time") {
    forall(Gen.choose(1L, 10000000L)) { ahead =>
      expect.same("waiting under a minute", Waiting.age(ListedAt(ahead), 0))
    }
  }
}
