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
        case round: GameState.InProgress =>
          val expected = round.phase match {
            case Phase.AwaitingDiscard(_, _) => 11
            case _                           => 10
          }

          expect.eql(round.table.hands(round.onTurn).size, expected)
        case _: GameState.Finished => success
      }
    }
  }

  test("the turn passes to the other player after a discard and at no other time") {
    forall(GameGen.partWayThrough(12)) { state =>
      state match {
        case round: GameState.InProgress =>
          expect(GameGen.legalMoves(state).forall {
            case (Move.Discard(_), next: GameState.InProgress) =>
              next.onTurn == round.onTurn.other
            case (_, next: GameState.InProgress) => next.onTurn == round.onTurn
            case (_, _: GameState.Finished)      => true
          })
        case _: GameState.Finished => success
      }
    }
  }

  test("the player who is not on turn is refused whatever they try") {
    forall(GameGen.partWayThrough(12)) { state =>
      state match {
        case round: GameState.InProgress =>
          expect(everyMove.forall { move =>
            GameState(state, round.onTurn.other, move) == Left(GameError.NotYourTurn)
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

  private val seats     = Seats(Player.Two)
  private val nonDealer = seats.nonDealer

  private val dealt  = GameState.deal(Deck.ordered, seats)
  private val upcard = dealt.table.discard.head
  private val taken  = GameState(dealt, nonDealer, Move.DrawDiscard)

  pureTest("a card taken from the pile cannot go straight back onto it") {
    expect.eql(
      taken.flatMap(GameState(_, nonDealer, Move.Discard(upcard))),
      Left(GameError.CannotDiscardDrawnCard(upcard))
    )
  }

  pureTest("a card the player does not hold cannot be discarded") {
    val absent = Card(King, Clubs)

    expect.eql(
      taken.flatMap(GameState(_, nonDealer, Move.Discard(absent))),
      Left(GameError.CardNotHeld(absent))
    )
  }
}
