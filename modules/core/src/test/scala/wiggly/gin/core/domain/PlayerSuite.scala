package wiggly.gin.core.domain

import weaver.SimpleIOSuite

object PlayerSuite extends SimpleIOSuite {

  pureTest("the other player is never the player asking") {
    expect(Player.values.forall(player => player.other != player))
  }

  pureTest("crossing to the other player twice comes back") {
    expect(Player.values.forall(player => player.other.other == player))
  }
}
