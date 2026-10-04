package wiggly.gin.ui

import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{GameId, Player, PlayerView, Token}
import wiggly.gin.core.port.Credentials

object UpdateSuite extends SimpleIOSuite {

  private val seat = Credentials(GameId("g1"), Player.One, Token("t1"))

  private val waiting: PlayerView = PlayerView.AwaitingOpponent(GameId("g1"), Player.One)

  pureTest("taking a seat moves off the start screen") {
    val (model, _) = Update(Model.Start("", None), Msg.Took(seat))
    expect.same(Model.Connecting(seat), model)
  }

  pureTest("the first view seats the player") {
    val (model, _) = Update(Model.Connecting(seat), Msg.Saw(waiting))
    expect.same(Model.Seated(seat, waiting, None), model)
  }

  pureTest("a later view replaces the view and clears the last error") {
    val (model, _) = Update(Model.Seated(seat, waiting, Some("stale")), Msg.Saw(waiting))
    expect.same(Model.Seated(seat, waiting, None), model)
  }

  pureTest("a failure keeps the seat and shows the reason") {
    val (model, _) = Update(Model.Seated(seat, waiting, None), Msg.Failed("nope"))
    expect.same(Model.Seated(seat, waiting, Some("nope")), model)
  }

  pureTest("a failure before a seat exists stays on the start screen") {
    val (model, _) = Update(Model.Start("g2", None), Msg.Failed("nope"))
    expect.same(Model.Start("g2", Some("nope")), model)
  }

  pureTest("typing a game id is remembered") {
    val (model, _) = Update(Model.Start("", None), Msg.JoiningChanged("g2"))
    expect.same(Model.Start("g2", None), model)
  }
}
