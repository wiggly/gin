package wiggly.gin.server.adapter.http

import cats.implicits.*
import io.circe.Json
import io.circe.syntax.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.*
import wiggly.gin.core.domain.Rank.*
import wiggly.gin.core.domain.Suit.*
import wiggly.gin.gen.GameGen

import Codecs.given

object CodecsSuite extends SimpleIOSuite with Checkers {

  pureTest("a card is its rank and its suit, spelled out") {
    expect.eql(
      Card(Ace, Spades).asJson,
      Json.obj("rank" -> Json.fromString("ace"), "suit" -> Json.fromString("spades"))
    )
  }

  pureTest("a move that needs no card is a single tag") {
    expect.eql(Json.obj("move" -> Json.fromString("draw-stock")).as[Move], Right(Move.DrawStock))
  }

  pureTest("a move that discards names the card") {
    expect.eql(
      Json
        .obj(
          "move" -> Json.fromString("knock"),
          "card" -> Json.obj("rank" -> Json.fromString("ten"), "suit" -> Json.fromString("hearts"))
        )
        .as[Move],
      Right(Move.Knock(Card(Ten, Hearts)))
    )
  }

  pureTest("a move nobody has heard of is refused rather than guessed at") {
    expect(Json.obj("move" -> Json.fromString("fold")).as[Move].isLeft)
  }

  pureTest("a discard with no card is refused") {
    expect(Json.obj("move" -> Json.fromString("discard")).as[Move].isLeft)
  }

  test("every move survives the trip out and back") {
    forall(GameGen.move) { move =>
      expect.eql(move.asJson.as[Move], Right(move))
    }
  }

  test("every card survives the trip out and back") {
    forall(GameGen.deck) { deck =>
      expect.eql(deck.cards.asJson.as[List[Card]], Right(deck.cards))
    }
  }

  test("a view carries the player's own cards and the sizes of everything else") {
    forall(GameGen.gamePlayed(6)) { game =>
      game match {
        case playing: Game.InPlay => {
          val view  = PlayerView.of(playing, Player.One).asJson
          val other = playing.round.table.hands(Player.Two).size

          expect.eql(view.hcursor.get[String]("state"), Right("in-play")) and
            expect.eql(view.hcursor.get[String]("you"), Right("one")) and
            expect.eql(view.hcursor.get[Int]("opponentSize"), Right(other)) and
            expect.eql(
              view.hcursor.get[Int]("stockSize"),
              Right(playing.round.table.stock.size)
            )
        }
        case _ => success
      }
    }
  }

  pureTest("the round that just ended carries who knocked, both hands and the points") {
    val gin  = Rank.values.toList.take(GameState.HandSize).map(Card(_, Spades))
    val held = List(
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

    val seats  = Seats(Player.Two)
    val result = RoundResult.of(GameGen.knockKeeping(seats, gin, held)).asJson

    expect.eql(result.hcursor.downField("outcome").get[String]("knockedBy"), Right("one")) and
      expect.eql(result.hcursor.downField("score").get[String]("result"), Right("gin")) and
      expect.eql(result.hcursor.downField("score").get[Int]("points"), Right(104)) and
      expect.eql(result.hcursor.downField("knocker").get[Int]("deadwoodValue"), Right(0)) and
      expect.eql(result.hcursor.downField("defender").get[Int]("deadwoodValue"), Right(79))
  }

  pureTest("a refusal is named as a client reads it, capitals and all") {
    expect.eql(Codecs.reason(GameFault.NotAPlayer), "not-a-player") and
      expect.eql(Codecs.reason(GameFault.NoSuchGame), "no-such-game") and
      expect.eql(
        Codecs.reason(GameFault.Illegal(GameError.CannotDiscardDrawnCard(Card(Ace, Spades)))),
        "cannot-discard-drawn-card"
      ) and
      expect.eql(Codecs.reason(GameFault.Illegal(GameError.NotYourTurn)), "not-your-turn")
  }

  pureTest("a game waiting for an opponent says which state it is in") {
    val view = (PlayerView.AwaitingOpponent(GameId("a-game"), Player.One): PlayerView).asJson

    expect.eql(view.hcursor.get[String]("state"), Right("awaiting-opponent")) and
      expect.eql(view.hcursor.get[String]("id"), Right("a-game"))
  }
}
