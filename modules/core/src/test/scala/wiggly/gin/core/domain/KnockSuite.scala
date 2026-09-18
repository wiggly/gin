package wiggly.gin.core.domain

import weaver.SimpleIOSuite
import wiggly.gin.core.domain.Player.NonDealer
import wiggly.gin.core.domain.Rank.*
import wiggly.gin.core.domain.Suit.*

object KnockSuite extends SimpleIOSuite {

  /** A card no case below wants, dealt so that there is always something to throw away.
    *
    * A knock cannot discard the card it has just taken from the pile, so every case here takes the
    * last card of the hand it means to keep and throws this one instead.
    */
  private val spare = Card(King, Clubs)

  /** A real deal, arranged so that the non-dealer ends a knock holding exactly `kept`.
    *
    * Every state asserted on below is therefore one a round can reach, rather than a table
    * assembled by hand that might not hold a deck at all.
    */
  private def knockKeeping(kept: List[Card]): Either[GameError, GameState] = {
    val upcard              = kept.last
    val hand                = kept.init :+ spare
    val rest                = Deck.ordered.cards.diff(hand :+ upcard)
    val (dealerHand, stock) = rest.splitAt(GameState.HandSize)

    val deck = Deck
      .from(hand ++ dealerHand ++ List(upcard) ++ stock)
      .getOrElse(sys.error("the cards this test chose do not make a deck"))

    GameState(GameState.deal(deck), NonDealer, Move.DrawDiscard)
      .flatMap(GameState(_, NonDealer, Move.Knock(spare)))
  }

  private def outcomeOf(result: Either[GameError, GameState]): Either[GameError, Option[Outcome]] =
    result.map {
      case GameState.Finished(_, outcome) => Some(outcome)
      case _: GameState.InProgress        => None
    }

  /** Two runs and four loose cards worth ten between them. */
  private val tenOfDeadwood = List(
    Card(Ace, Spades),
    Card(Two, Spades),
    Card(Three, Spades),
    Card(Five, Hearts),
    Card(Six, Hearts),
    Card(Seven, Hearts),
    Card(Ace, Clubs),
    Card(Three, Clubs),
    Card(Two, Diamonds),
    Card(Four, Diamonds)
  )

  /** The same hand with the four of diamonds swapped for the five, one pip over the threshold. */
  private val elevenOfDeadwood = tenOfDeadwood.init :+ Card(Five, Diamonds)

  /** Ten spades in sequence, which is one run and nothing left over. */
  private val gin = Rank.values.toList.take(GameState.HandSize).map(Card(_, Spades))

  pureTest("a knock leaving exactly ten deadwood is allowed") {
    expect.eql(
      knockKeeping(tenOfDeadwood).map(_.table.hands(NonDealer).deadwoodValue),
      Right(10)
    )
  }

  pureTest("a knock leaving exactly ten deadwood ends the round") {
    expect.eql(outcomeOf(knockKeeping(tenOfDeadwood)), Right(Some(Outcome.Knocked(NonDealer))))
  }

  pureTest("a knock leaving eleven deadwood is one pip too many") {
    expect.eql(knockKeeping(elevenOfDeadwood), Left(GameError.CannotKnock(11)))
  }

  pureTest("a hand that melds completely knocks with nothing left, which is gin") {
    expect.eql(knockKeeping(gin).map(_.table.hands(NonDealer).deadwoodValue), Right(0))
  }

  pureTest("a finished round keeps the knocker's hand for the scoring to read") {
    expect.eql(knockKeeping(gin).map(_.table.hands(NonDealer).cards), Right(gin.sorted))
  }

  pureTest("a finished round keeps the other player's hand too") {
    expect.eql(knockKeeping(gin).map(_.table.hands(Player.Dealer).size), Right(GameState.HandSize))
  }

  pureTest("a finished round refuses every move from either player") {
    val finished = knockKeeping(gin)
    val moves    =
      List(Move.DrawStock, Move.DrawDiscard, Move.Pass, Move.Discard(spare), Move.Knock(spare))

    expect(moves.forall { move =>
      Player.values.forall { player =>
        finished.flatMap(GameState(_, player, move)) == Left(GameError.RoundOver)
      }
    })
  }
}
