package wiggly.gin.core.domain

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.Rank.*
import wiggly.gin.core.domain.Suit.*
import wiggly.gin.gen.GameGen

object TurnSuite extends SimpleIOSuite with Checkers {

  private val everyMove =
    List(
      Move.DrawStock,
      Move.DrawDiscard,
      Move.Pass,
      Move.Discard(Card(Ace, Spades)),
      Move.Knock(Card(Ace, Spades))
    )

  test("a round holds the whole deck, and holds it once, however far it has been played") {
    forall(GameGen.walked()) { state =>
      expect.eql(GameGen.allCards(state).sorted, Deck.ordered.cards)
    }
  }

  test("no legal move loses a card or invents one") {
    forall(GameGen.partWayThrough(12)) { state =>
      expect(GameGen.legalMoves(state).forall { (_, next) =>
        GameGen.allCards(next).sorted == Deck.ordered.cards
      })
    }
  }

  test("the player on turn holds eleven cards while owing a discard and ten otherwise") {
    forall(GameGen.partWayThrough(12)) { state =>
      state match {
        case GameState.InProgress(table, phase) =>
          val expected = phase match {
            case Phase.AwaitingDiscard(_, _) => 11
            case _                           => 10
          }

          expect.eql(table.hands(phase.onTurn).size, expected)
        case _: GameState.Finished => success
      }
    }
  }

  test("the turn passes to the other player after a discard and at no other time") {
    forall(GameGen.partWayThrough(12)) { state =>
      state match {
        case GameState.InProgress(_, phase) =>
          expect(GameGen.legalMoves(state).forall {
            case (Move.Discard(_), GameState.InProgress(_, next)) =>
              next.onTurn == phase.onTurn.other
            case (_, GameState.InProgress(_, next)) => next.onTurn == phase.onTurn
            case (_, _: GameState.Finished)         => true
          })
        case _: GameState.Finished => success
      }
    }
  }

  test("the player who is not on turn is refused whatever they try") {
    forall(GameGen.partWayThrough(12)) { state =>
      state match {
        case GameState.InProgress(_, phase) =>
          expect(everyMove.forall { move =>
            GameState(state, phase.onTurn.other, move) == Left(GameError.NotYourTurn)
          })
        case _: GameState.Finished => success
      }
    }
  }

  test("a round that is still going always has a move available") {
    forall(GameGen.partWayThrough(12)) { state =>
      state match {
        case _: GameState.InProgress => expect(GameGen.legalMoves(state).nonEmpty)
        case _: GameState.Finished   => success
      }
    }
  }

  test("playing legal moves always finishes the round") {
    forall(GameGen.walked()) { state =>
      expect(state.isInstanceOf[GameState.Finished])
    }
  }

  private val dealt  = GameState.deal(Deck.ordered)
  private val upcard = dealt.table.discard.head
  private val taken  = GameState(dealt, Player.NonDealer, Move.DrawDiscard)

  pureTest("a card taken from the pile cannot go straight back onto it") {
    expect.eql(
      taken.flatMap(GameState(_, Player.NonDealer, Move.Discard(upcard))),
      Left(GameError.CannotDiscardDrawnCard(upcard))
    )
  }

  pureTest("a card the player does not hold cannot be discarded") {
    val absent = Card(King, Clubs)

    expect.eql(
      taken.flatMap(GameState(_, Player.NonDealer, Move.Discard(absent))),
      Left(GameError.CardNotHeld(absent))
    )
  }
}
