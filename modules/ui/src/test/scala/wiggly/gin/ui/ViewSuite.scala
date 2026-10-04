package wiggly.gin.ui

import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{GameId, Player, PlayerView, Token}
import wiggly.gin.core.port.Credentials

object ViewSuite extends SimpleIOSuite {

  private val seat = Credentials(GameId("g1"), Player.One, Token("t1"))

  private val waiting: PlayerView = PlayerView.AwaitingOpponent(GameId("g1"), Player.One)

  pureTest("the start screen offers both ways in") {
    val rendered = render(Model.Start("", None))

    expect(rendered.contains("Create a game")) and expect(rendered.contains("Join"))
  }

  pureTest("the waiting screen shows the game to share") {
    expect(render(Model.Seated(seat, waiting, None)).contains("g1"))
  }

  pureTest("an error is put in front of the player") {
    val rendered = render(Model.Seated(seat, waiting, Some("that is not your turn")))

    expect(rendered.contains("that is not your turn"))
  }

  pureTest("a token is never drawn on any screen") {
    val rendered = render(Model.Seated(seat, waiting, None)) + render(Model.Connecting(seat))

    expect(!rendered.contains("t1"))
  }

  private def render(model: Model): String = View(model).toString
}
