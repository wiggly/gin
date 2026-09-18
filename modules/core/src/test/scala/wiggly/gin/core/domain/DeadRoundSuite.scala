package wiggly.gin.core.domain

import weaver.SimpleIOSuite

object DeadRoundSuite extends SimpleIOSuite {

  private val seats     = Seats(Player.Two)
  private val dealer    = seats.dealer
  private val nonDealer = seats.nonDealer

  private val opened = for {
    passed  <- GameState(GameState.deal(Deck.ordered, seats), nonDealer, Move.Pass)
    refused <- GameState(passed, dealer, Move.Pass)
    drawn   <- GameState(refused, nonDealer, Move.DrawStock)
  } yield drawn

  /** The round played straight down the stock: draw, discard, draw, discard, stopping at the point
    * where the player on turn owes the discard that would leave two cards in the stock.
    */
  private val owingTheLastDiscard: GameState = {
    @annotation.tailrec
    def drain(state: GameState): GameState = {
      state match {
        case GameState.InProgress(table, _, Phase.AwaitingDiscard(player, _))
            if table.stock.size > GameState.StockFloor => {
          val turn = for {
            discarded <- GameState(state, player, Move.Discard(table.hands(player).cards.head))
            drawn     <- GameState(discarded, player.other, Move.DrawStock)
          } yield drawn

          drain(turn.getOrElse(sys.error(s"could not play the stock down: $turn")))
        }
        case settled => settled
      }
    }

    drain(opened.getOrElse(sys.error("could not open the round")))
  }

  private def owed: (Player, Card) = {
    owingTheLastDiscard match {
      case GameState.InProgress(table, _, Phase.AwaitingDiscard(player, _)) =>
        (player, table.hands(player).cards.head)
      case other => sys.error(s"the round did not reach the last discard: $other")
    }
  }

  pureTest("playing the stock down leaves two cards and a discard owed") {
    expect.eql(owingTheLastDiscard.table.stock.size, GameState.StockFloor)
  }

  pureTest("the discard that leaves two cards in the stock kills the round") {
    val (player, card) = owed

    expect.eql(
      GameState(owingTheLastDiscard, player, Move.Discard(card)).map {
        case GameState.Finished(_, _, outcome) => outcome
        case _: GameState.InProgress           => Outcome.Knocked(player)
      },
      Right(Outcome.Dead)
    )
  }

  pureTest("a dead round still holds every card for the redeal") {
    val (player, card) = owed

    expect.eql(
      GameState(owingTheLastDiscard, player, Move.Discard(card)).map(state =>
        (state.table.stock ++ state.table.discard ++ state.table.hands.cards).sorted
      ),
      Right(Deck.ordered.cards)
    )
  }

  pureTest("a knock on that last discard is answered as a knock and never as a dead round") {
    val (player, card) = owed

    expect(GameState(owingTheLastDiscard, player, Move.Knock(card)) match {
      case Right(GameState.Finished(_, _, Outcome.Dead)) => false
      case _                                             => true
    })
  }
}
