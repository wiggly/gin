package wiggly.gin.ui

import weaver.SimpleIOSuite
import wiggly.gin.core.domain.{GameId, Player, Token}
import wiggly.gin.core.port.Credentials

object SeatSuite extends SimpleIOSuite {

  private val seat = Credentials(GameId("g1"), Player.Two, Token("t1"))

  pureTest("a seat survives being written down and read back") {
    expect.same(Some(seat), Seat.decode(seat.id, Seat.encode(seat)))
  }

  pureTest("a token holding a colon survives, because only the first one splits") {
    val awkward = seat.copy(token = Token("a:b:c"))

    expect.same(Some(awkward), Seat.decode(awkward.id, Seat.encode(awkward)))
  }

  pureTest("each game is written under its own key") {
    expect(Seat.key(GameId("g1")) != Seat.key(GameId("g2")))
  }

  pureTest("something that is not a seat is refused rather than guessed at") {
    expect.same(None, Seat.decode(GameId("g1"), "Nobody:t1")) and
      expect.same(None, Seat.decode(GameId("g1"), "rubbish"))
  }

  pureTest("a game id is read out of a play path") {
    expect.same(Some("g1"), Seat.gameIdIn("/play/g1"))
  }

  pureTest("the root path names no game") {
    expect.same(None, Seat.gameIdIn("/"))
  }

  pureTest("a path the server answers for itself names no game") {
    expect.same(None, Seat.gameIdIn("/health")) and
      expect.same(None, Seat.gameIdIn("/api/v1/games"))
  }

  pureTest("the link to a game is the path that opens it") {
    expect.same(Some("g1"), Seat.gameIdIn(Seat.linkTo(GameId("g1"))))
  }
}
