package wiggly.gin.ui

import cats.Show
import tyrian.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.{GameId, Player, PlayerView, Token}
import wiggly.gin.core.port.Credentials
import wiggly.gin.gen.ViewGen
import wiggly.gin.gen.ViewGen.held

object ViewSuite extends SimpleIOSuite with Checkers {

  private given Show[PlayerView.InPlay] = Show.fromToString

  private val seat = Credentials(GameId("g1"), Player.One, Token("t1"))

  private val waiting = new PlayerView.AwaitingOpponent(GameId("g1"), Player.One)

  pureTest("the start screen offers both ways in") {
    val rendered = drawn(Model.Start("", None))

    expect(rendered.contains("Create a game")).and(expect(rendered.contains("Join")))
  }

  pureTest("the waiting screen shows the game to share") {
    expect(drawn(Model.Seated(seat, waiting, None)).contains("g1"))
  }

  pureTest("an error is put in front of the player") {
    val rendered = drawn(Model.Seated(seat, waiting, Some("that is not your turn")))

    expect(rendered.contains("that is not your turn"))
  }

  pureTest("a token is never drawn on any screen") {
    val rendered = drawn(Model.Seated(seat, waiting, None)) + drawn(Model.Connecting(seat))

    expect(!rendered.contains("t1"))
  }

  test("every card in the hand is drawn with its name") {
    forall(ViewGen.inPlay) { view =>
      val rendered = drawn(playing(view))

      expect(held(view).forall(card => rendered.contains(s"""aria-label="${Cards.name(card)}"""")))
    }
  }

  test("the other hand is drawn face down, one back for each card, beside the stock") {
    forall(ViewGen.inPlay) { view =>
      val backs = """aria-label="a face-down card"""".r.findAllIn(drawn(playing(view))).size

      expect.same(view.opponentSize + 1, backs)
    }
  }

  private def playing(view: PlayerView.InPlay): Model =
    Model.Playing(seat, view, Holding.of(view), None)

  private def drawn(model: Model): String = View(model).render
}
