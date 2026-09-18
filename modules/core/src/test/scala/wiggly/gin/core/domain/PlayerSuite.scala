package wiggly.gin.core.domain

import weaver.SimpleIOSuite

object PlayerSuite extends SimpleIOSuite {

  pureTest("the other player is never the player asking") {
    expect(Player.values.forall(player => player.other != player))
  }

  pureTest("crossing to the other player twice comes back") {
    expect(Player.values.forall(player => player.other.other == player))
  }

  pureTest("the dealer never deals to themselves") {
    expect(Player.values.forall(player => Seats(player).nonDealer != player))
  }

  pureTest("passing the deal twice comes back to the player who started with it") {
    expect(Player.values.forall(player => Seats(player).passed.passed == Seats(player)))
  }

  pureTest("passing the deal gives it to the player who did not have it") {
    expect(Player.values.forall(player => Seats(player).passed.dealer == Seats(player).nonDealer))
  }
}
