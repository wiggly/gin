package wiggly.gin.core.domain

import cats.Show
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.Rank.*
import wiggly.gin.core.domain.Suit.*
import wiggly.gin.gen.GameGen

object MovesSuite extends SimpleIOSuite with Checkers {

  // Game has one, but weaver wants it for the case the generator is typed as.
  private given Show[Game.InPlay] = Show.fromToString

  test("every move offered is accepted by the rules") {
    forall(GameGen.inPlay) { game =>
      val onTurn = game.round.onTurn

      val refused = Moves.available(viewOf(game, onTurn)).filter { move =>
        GameState(game.round, onTurn, move).isLeft
      }

      expect(refused.isEmpty)
    }
  }

  test("the moves offered are exactly the ones the rules accept") {
    forall(GameGen.inPlay) { game =>
      val onTurn   = game.round.onTurn
      val offered  = Moves.available(viewOf(game, onTurn)).toSet
      val accepted = GameGen.legalMoves(game.round).map(_._1).toSet

      expect.same(accepted, offered)
    }
  }

  test("a player off turn is offered nothing") {
    forall(GameGen.inPlay) { game =>
      expect(Moves.available(viewOf(game, game.round.onTurn.other)).isEmpty)
    }
  }

  /** A random walk reaches a legal knock only by luck, so the branch is pinned by hand.
    *
    * The hand is two runs and four loose cards worth ten between them. Discarding the card taken
    * from the pile is not allowed, so the knock offered is on one of the ten that were dealt.
    */
  pureTest("a hand at the knock boundary is offered a knock") {
    expect(Moves.available(knockingView).exists {
      case _: Move.Knock => true
      case _             => false
    })
  }

  pureTest("the card just taken from the pile is never offered as a discard") {
    val view = knockingView

    val taken = view.phase match {
      case Phase.AwaitingDiscard(_, card) => card
      case other                          => sys.error(s"expected a discard owed, got $other")
    }

    val named = Moves.available(view).collect {
      case Move.Discard(card) => card
      case Move.Knock(card)   => card
    }

    expect(taken.isDefined) and expect(!named.exists(taken.contains))
  }

  pureTest("a hand far above the threshold is offered no knock") {
    val moves = Moves.available(looseView)

    expect(moves.collect { case knock: Move.Knock => knock }.isEmpty) and
      expect(moves.exists {
        case _: Move.Discard => true
        case _               => false
      })
  }

  /** Two runs and four loose cards worth ten between them, which is a knock right on the mark. */
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

  /** Ten cards that meld into nothing and are worth far more than the threshold. */
  private val wellOver = List(
    Card(King, Spades),
    Card(Queen, Hearts),
    Card(Jack, Clubs),
    Card(Ten, Diamonds),
    Card(Eight, Spades),
    Card(Six, Hearts),
    Card(Four, Clubs),
    Card(Two, Diamonds),
    Card(Nine, Spades),
    Card(Seven, Hearts)
  )

  private val knockingView = viewAwaitingDiscard(tenOfDeadwood)

  private val looseView = viewAwaitingDiscard(wellOver)

  /** The named hand, played up to the point where its holder owes a discard. */
  private def viewAwaitingDiscard(knocks: List[Card]): PlayerView.InPlay = {
    val seats = Seats(Player.Two)
    val holds = Deck.ordered.cards.diff(knocks).take(GameState.HandSize)
    val deal  = GameGen.knockingDeal(knocks, holds)

    val round =
      GameState(GameState.deal(deal.deck, seats), seats.nonDealer, Move.DrawDiscard) match {
        case Right(playing: GameState.InProgress) => playing
        case other => sys.error(s"the cards this test chose did not reach a discard: $other")
      }

    val game = Game.InPlay(
      id = GameId("g1"),
      tokens = Tokens(Token("t1"), Token("t2")),
      ledger = Match.start(seats.dealer),
      round = round,
      previous = None
    )

    viewOf(game, seats.nonDealer)
  }

  private def viewOf(game: Game.InPlay, you: Player): PlayerView.InPlay = {
    PlayerView.of(game, you) match {
      case view: PlayerView.InPlay => view
      case other                   => sys.error(s"expected a round in play, got $other")
    }
  }
}
