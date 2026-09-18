package wiggly.gin.core.domain

import weaver.SimpleIOSuite
import wiggly.gin.core.domain.Player.{Dealer, NonDealer}
import wiggly.gin.gen.GameGen

object OpeningSuite extends SimpleIOSuite {

  private val dealt  = GameState.deal(Deck.ordered)
  private val upcard = dealt.table.discard.head

  private def play(state: GameState, moves: (Player, Move)*): Either[GameError, GameState] =
    moves.foldLeft(Right(state): Either[GameError, GameState]) { (soFar, playerAndMove) =>
      soFar.flatMap(GameState(_, playerAndMove._1, playerAndMove._2))
    }

  private def phaseAfter(moves: (Player, Move)*): Either[GameError, Option[Phase]] =
    play(dealt, moves*).map(GameGen.phase)

  pureTest("the non-dealer can take the upcard and then owes a discard") {
    val taken = play(dealt, NonDealer -> Move.DrawDiscard)

    expect.eql(
      taken.map(GameGen.phase),
      Right(Some(Phase.AwaitingDiscard(NonDealer, Some(upcard))))
    ) and
      expect.eql(taken.map(_.table.hands(NonDealer).size), Right(11)) and
      expect.eql(taken.map(_.table.discard), Right(Nil))
  }

  pureTest("a non-dealer who passes hands the same offer to the dealer") {
    expect.eql(phaseAfter(NonDealer -> Move.Pass), Right(Some(Phase.UpcardOffered(Dealer))))
  }

  pureTest("the dealer can take the upcard the non-dealer refused") {
    val taken = play(dealt, NonDealer -> Move.Pass, Dealer -> Move.DrawDiscard)

    expect.eql(
      taken.map(GameGen.phase),
      Right(Some(Phase.AwaitingDiscard(Dealer, Some(upcard))))
    ) and
      expect.eql(taken.map(_.table.hands(Dealer).size), Right(11))
  }

  pureTest("when both players pass the non-dealer is left to draw from the stock") {
    val passed = play(dealt, NonDealer -> Move.Pass, Dealer -> Move.Pass)

    expect.eql(passed.map(GameGen.phase), Right(Some(Phase.AwaitingOpeningDraw)))
  }

  pureTest("the opening draw takes the top of the stock and leaves the upcard alone") {
    val drawn =
      play(dealt, NonDealer -> Move.Pass, Dealer -> Move.Pass, NonDealer -> Move.DrawStock)

    expect.eql(drawn.map(GameGen.phase), Right(Some(Phase.AwaitingDiscard(NonDealer, None)))) and
      expect.eql(drawn.map(_.table.stock.size), Right(30)) and
      expect.eql(drawn.map(_.table.discard), Right(List(upcard)))
  }

  pureTest("the stock is closed while the upcard is still on offer") {
    expect.eql(play(dealt, NonDealer -> Move.DrawStock), Left(GameError.StockClosed))
  }

  pureTest("the pile is closed to the player who just refused it") {
    val refused =
      play(dealt, NonDealer -> Move.Pass, Dealer -> Move.Pass, NonDealer -> Move.DrawDiscard)

    expect.eql(refused, Left(GameError.PileClosed))
  }

  pureTest("the dealer cannot take an upcard offered to the non-dealer") {
    expect.eql(play(dealt, Dealer -> Move.DrawDiscard), Left(GameError.NotYourTurn))
  }

  pureTest("there is nothing to pass once the opening has settled") {
    val settled = play(dealt, NonDealer -> Move.Pass, Dealer -> Move.Pass, NonDealer -> Move.Pass)

    expect.eql(settled, Left(GameError.NothingToPass))
  }

  pureTest("a player who has not drawn cannot discard") {
    expect.eql(play(dealt, NonDealer -> Move.Discard(upcard)), Left(GameError.MustDraw))
  }
}
