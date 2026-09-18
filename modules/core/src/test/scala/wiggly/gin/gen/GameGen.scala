package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.domain.{
  Card,
  Deck,
  Game,
  GameId,
  GameState,
  Move,
  Phase,
  Player,
  Seats,
  Token
}

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

  /** A round played out with nobody ever knocking, which is the only way the stock runs down.
    *
    * The walk is the one [[walked]] takes with the knocks removed from what is on offer, so the
    * round it returns is always the dead one rather than a state assembled to look like it.
    */
  val ranOutOfStock: Gen[GameState] = dealt.flatMap(drain)

  private def drain(state: GameState): Gen[GameState] = {
    state match {
      case _: GameState.Finished   => Gen.const(state)
      case _: GameState.InProgress =>
        legalMoves(state).filter {
          case (_: Move.Knock, _) => false
          case _                  => true
        } match {
          case Nil   => sys.error(s"a round with no knock available ran out of moves: $state")
          case moves => Gen.oneOf(moves).flatMap((_, next) => drain(next))
        }
    }
  }

  /** A real deal arranged so that the knocker ends a knock holding exactly `knocks` and the other
    * player holds exactly `holds`.
    *
    * The round is played through the machine rather than assembled, so every state a test asserts
    * on is one a round can reach. The card thrown away to end the turn is whichever the two hands
    * left behind, because a knock cannot discard the card it has just taken from the pile.
    */
  def knockKeeping(seats: Seats, knocks: List[Card], holds: List[Card]): GameState.Finished = {
    val deal    = knockingDeal(knocks, holds)
    val knocker = seats.nonDealer

    val played = for {
      taken <- GameState(GameState.deal(deal.deck, seats), knocker, Move.DrawDiscard)
      ended <- GameState(taken, knocker, Move.Knock(deal.spare))
    } yield ended

    played match {
      case Right(round: GameState.Finished) => round
      case other => sys.error(s"the cards this test chose did not reach a knock: $other")
    }
  }

  /** A deck that deals the hands above, and the card the knocker throws to end the turn.
    *
    * It is the deck rather than the finished round, so that a test can play the same knock through
    * whatever drives the round: the state machine directly, or a whole game.
    */
  final case class KnockingDeal(deck: Deck, spare: Card)

  def knockingDeal(knocks: List[Card], holds: List[Card]): KnockingDeal = {
    val upcard = knocks.last
    val spare  = Deck.ordered.cards.diff(knocks ++ holds).head
    val dealt  = knocks.init :+ spare
    val rest   = Deck.ordered.cards.diff(dealt ++ holds ++ List(upcard))

    val deck = Deck
      .from(dealt ++ holds ++ List(upcard) ++ rest)
      .getOrElse(sys.error("the cards this test chose do not make a deck"))

    KnockingDeal(deck, spare)
  }

  /** Any move, legal here or not, which is what a codec has to carry. */
  val move: Gen[Move] = Gen.oneOf(
    Gen.const(Move.DrawStock),
    Gen.const(Move.DrawDiscard),
    Gen.const(Move.Pass),
    CardGen.card.map(Move.Discard.apply),
    CardGen.card.map(Move.Knock.apply)
  )

  val gameId: Gen[GameId] = Gen.uuid.map(id => GameId(id.toString))

  val token: Gen[Token] = Gen.uuid.map(id => Token(id.toString))

  /** A game created, joined, and then played some way in by taking legal moves at random.
    *
    * Every move is handed a fresh deck for the deal it might set off, which is what the service
    * does for the same reason: the store applies a pure change, so the cards for the next round
    * have to arrive with the move.
    */
  def gamePlayed(steps: Int): Gen[Game] = for {
    id    <- gameId
    host  <- token
    guest <- token
    first <- deck
    game = Game
      .AwaitingOpponent(id, host)
      .joined(guest, first)
      .getOrElse(sys.error("a waiting game refused an opponent"))
    played <- advance(game, steps)
  } yield played

  private def advance(game: Game, remaining: Int): Gen[Game] = {
    game match {
      case playing: Game.InPlay if remaining > 0 =>
        legalMoves(playing.round) match {
          case Nil   => Gen.const(game)
          case moves =>
            for {
              (move, _) <- Gen.oneOf(moves)
              next      <- deck
              stepped = playing
                .played(playing.round.onTurn, move, next)
                .getOrElse(sys.error(s"a legal move was refused: $move"))
              rest <- advance(stepped, remaining - 1)
            } yield rest
        }
      case settled => Gen.const(settled)
    }
  }

  /** A round in progress, some way into its play. */
  def partWayThrough(steps: Int): Gen[GameState] = dealt.flatMap(walk(_, steps))

}
