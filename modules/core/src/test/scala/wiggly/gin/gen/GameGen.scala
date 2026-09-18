package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.domain.{Card, Deck, GameState, Move, Phase, Player, Seats}

object GameGen {

  /** A deck in an arbitrary order, which is what a shuffle hands the deal. */
  val deck: Gen[Deck] = CardGen.shuffledDeck.map(cards =>
    Deck.from(cards).getOrElse(sys.error("generator produced cards that are not a deck"))
  )

  /** Which of the two players dealt. Every round is generated against both, because the opening
    * is the one part of the round that reads the seating.
    */
  val seats: Gen[Seats] = Gen.oneOf(Player.values.toList).map(Seats.apply)

  val dealt: Gen[GameState] = for {
    deck  <- deck
    seats <- seats
  } yield GameState.deal(deck, seats)

  /** What a round is waiting for, or nothing once it is over.
    *
    * A finished round has no phase, which is the whole point of the split in [[GameState]]. This is
    * how a test reads one without the production type growing an accessor for the two thirds of its
    * cases that cannot answer.
    */
  def phase(state: GameState): Option[Phase] = {
    state match {
      case GameState.InProgress(_, _, phase) => Some(phase)
      case _: GameState.Finished             => None
    }
  }

  /** Every card the round is holding, which is the four places a card can be and no others.
    *
    * `AwaitingDiscard` also carries the card taken from the pile this turn, but that card is in the
    * player's hand as well. It is a note for a guard to read rather than a place a card sits, so
    * counting it would find 53 cards in a round that is perfectly well formed.
    */
  def allCards(state: GameState): List[Card] = {
    val table = state.table

    table.stock ++ table.discard ++ table.hands.cards
  }

  /** Every move the round would accept from the player on turn.
    *
    * It asks the machine rather than reimplementing the rules, which is the point: a property built
    * on it cannot pass by agreeing with a second, equally wrong copy of the guards.
    */
  def legalMoves(state: GameState): List[(Move, GameState)] = {
    state match {
      case _: GameState.Finished       => Nil
      case round: GameState.InProgress => {
        val discards = round.table.hands(round.onTurn).cards.flatMap { card =>
          List(Move.Discard(card), Move.Knock(card))
        }

        (List(Move.DrawStock, Move.DrawDiscard, Move.Pass) ++ discards).flatMap { move =>
          GameState(state, round.onTurn, move).toOption.map(move -> _)
        }
      }
    }
  }

  /** The round played out by taking a legal move at random until it finishes.
    *
    * The cap is generous rather than tight. A round cannot outlast its stock, and the point of the
    * cap is to fail a machine that loops rather than to pin the length of a game.
    */
  def walked(limit: Int = 200): Gen[GameState] = dealt.flatMap(walk(_, limit))

  private def walk(state: GameState, remaining: Int): Gen[GameState] = {
    legalMoves(state) match {
      case Nil                 => Gen.const(state)
      case _ if remaining <= 0 => Gen.const(state)
      case moves               => Gen.oneOf(moves).flatMap((_, next) => walk(next, remaining - 1))
    }
  }

  /** A round in progress, some way into its play. */
  def partWayThrough(steps: Int): Gen[GameState] = dealt.flatMap(walk(_, steps))

}
