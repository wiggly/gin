package wiggly.gin.core.domain

import cats.implicits.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.Rank.*
import wiggly.gin.core.domain.Suit.*
import wiggly.gin.gen.GameGen

object GameSuite extends SimpleIOSuite with Checkers {

  private val id    = GameId("a-game")
  private val host  = Token("host")
  private val guest = Token("guest")

  private val waiting = Game.AwaitingOpponent(id, host)

  private def joined(deck: Deck = Deck.ordered): Game =
    waiting.joined(guest, deck).getOrElse(sys.error("a waiting game refused an opponent"))

  private def inPlay(game: Game): Game.InPlay = {
    game match {
      case playing: Game.InPlay => playing
      case other                => sys.error(s"the game is not in play: $other")
    }
  }

  pureTest("a game waiting for an opponent answers to the host and nobody else") {
    expect.eql(waiting.holder(host), Some(Player.One)) and
      expect.eql(waiting.holder(guest), None)
  }

  pureTest("joining seats the guest and answers to both tokens") {
    val game = joined()

    expect.eql(game.holder(host), Some(Player.One)) and
      expect.eql(game.holder(guest), Some(Player.Two))
  }

  pureTest("the player who joins deals, so the upcard is offered to the host") {
    val game = inPlay(joined())

    expect.eql(game.round.seats, Seats(Player.Two)) and
      expect.eql(game.round.phase, Phase.UpcardOffered(Player.One)) and
      expect.eql(game.ledger.seats, game.round.seats)
  }

  pureTest("a joined game has dealt a round and scored nothing") {
    val game = inPlay(joined())

    expect.eql(game.ledger.totals, Tally(0, 0)) and
      expect.eql(game.previous, None) and
      expect.eql(game.round.table.hands(Player.One).size, GameState.HandSize)
  }

  pureTest("a second opponent is turned away") {
    expect.eql(joined().joined(Token("third"), Deck.ordered), Left(GameFault.AlreadyFull))
  }

  pureTest("a move before anybody has joined is refused") {
    expect.eql(
      waiting.played(Player.One, Move.DrawDiscard, Deck.ordered),
      Left(GameFault.NotInPlay)
    )
  }

  pureTest("a move the rules refuse comes back carrying the rule's own error") {
    expect.eql(
      joined().played(Player.Two, Move.DrawDiscard, Deck.ordered),
      Left(GameFault.Illegal(GameError.NotYourTurn))
    )
  }

  /** Ace to ten of spades: one run and nothing left over. */
  private val gin = Rank.values.toList.take(GameState.HandSize).map(Card(_, Spades))

  /** Ten cards in two suits that make nothing at all, worth seventy-nine. */
  private val worthSeventyNine = List(
    Card(King, Hearts),
    Card(Queen, Diamonds),
    Card(Jack, Hearts),
    Card(Ten, Diamonds),
    Card(Nine, Hearts),
    Card(Eight, Diamonds),
    Card(Seven, Hearts),
    Card(Six, Diamonds),
    Card(Five, Hearts),
    Card(Four, Diamonds)
  )

  /** A hand worth ten, so that a knock against it settles a round without settling the match. */
  private val worthTen = List(
    Card(King, Diamonds),
    Card(Queen, Diamonds),
    Card(Jack, Diamonds),
    Card(Ten, Clubs),
    Card(Nine, Clubs),
    Card(Eight, Clubs),
    Card(Ace, Hearts),
    Card(Two, Clubs),
    Card(Three, Diamonds),
    Card(Four, Clubs)
  )

  /** The host, who is the non-dealer, takes the upcard and knocks with what it leaves them. */
  private def knocked(
      knocks: List[Card],
      holds: List[Card],
      next: Deck
  ): Either[GameFault, Game] = {
    val deal = GameGen.knockingDeal(knocks, holds)

    for {
      game  <- waiting.joined(guest, deal.deck)
      taken <- game.played(Player.One, Move.DrawDiscard, Deck.ordered)
      ended <- taken.played(Player.One, Move.Knock(deal.spare), next)
    } yield ended
  }

  pureTest("a round that ends is scored, folded into the ledger and followed by a fresh deal") {
    val next = Deck.from(Deck.ordered.cards.reverse).getOrElse(sys.error("not a deck"))
    val game = knocked(gin, worthTen, next).map(inPlay)

    expect.eql(game.map(_.ledger.totals), Right(Tally(35, 0))) and
      expect.eql(game.map(_.previous.map(_.score)), Right(Some(RoundScore.Gin(Player.One, 35)))) and
      expect.eql(game.map(_.round.table.stock.size), Right(31)) and
      expect.eql(game.map(_.round.phase), Right(Phase.UpcardOffered(Player.Two)))
  }

  pureTest("the deal passes to the other player for the round after a scored one") {
    val next = Deck.from(Deck.ordered.cards.reverse).getOrElse(sys.error("not a deck"))

    expect.eql(
      knocked(gin, worthTen, next).map(inPlay).map(_.round.seats),
      Right(Seats(Player.One))
    )
  }

  pureTest("a round that carries a player past a hundred ends the match") {
    val game = knocked(gin, worthSeventyNine, Deck.ordered)

    expect.eql(
      game.map {
        case over: Game.Over => over.ledger.result
        case other           => sys.error(s"the match did not end: $other")
      },
      Right(MatchResult(Player.One, Tally((104 + 100 + 25) * 2, 0)))
    )
  }

  pureTest("a finished match refuses every move") {
    val game = knocked(gin, worthSeventyNine, Deck.ordered)

    expect.eql(
      game.map(_.played(Player.One, Move.DrawStock, Deck.ordered)),
      Right(Left(GameFault.NotInPlay))
    )
  }

  test("a game holds one whole deck however far it has been played") {
    forall(GameGen.gamePlayed(14)) { game =>
      game match {
        case playing: Game.InPlay =>
          expect.eql(GameGen.allCards(playing.round).sorted, Deck.ordered.cards)
        case _ => success
      }
    }
  }

  test("a token neither player holds names nobody") {
    forall(GameGen.gamePlayed(6)) { game =>
      expect.eql(game.holder(Token("nobody")), None)
    }
  }
}
