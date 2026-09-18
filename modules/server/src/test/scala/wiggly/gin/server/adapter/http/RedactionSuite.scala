package wiggly.gin.server.adapter.http

import cats.implicits.*
import io.circe.Json
import io.circe.syntax.*
import weaver.{Expectations, SimpleIOSuite}
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.*
import wiggly.gin.gen.GameGen

import Codecs.given

/** What a player is told, checked against what they are entitled to know.
  *
  * It reads the encoded payload rather than the Scala value on purpose. A field added to a view
  * later appears in the JSON whether or not anybody remembered this suite, so the check keeps
  * working on code it was not written for.
  *
  * `previous` is set aside for most of what follows. A knock puts both hands on the table, so the
  * round that just ended is public in full, and its cards have since been shuffled back into a
  * fresh deal. That makes them say nothing about where anything is now, and it also means they
  * turn up in the stock of the round in play, which is why the claims below are about what a view
  * says of the round in play rather than about every card it mentions.
  */
object RedactionSuite extends SimpleIOSuite with Checkers {

  /** Every card mentioned anywhere in the payload, at any depth. */
  private def cardsIn(json: Json): List[Card] =
    json.as[Card].toOption.toList ++
      json.asArray.toList.flatten.flatMap(cardsIn) ++
      json.asObject.toList.flatMap(_.values.toList).flatMap(cardsIn)

  private def aboutTheRoundInPlay(payload: Json): List[Card] =
    cardsIn(payload.mapObject(_.remove("previous")))

  /** The card the phase names, which is the one card of the other player's hand that is public:
    * everybody watched it leave the discard pile.
    */
  private def takenFromThePile(round: GameState.InProgress): List[Card] =
    round.phase match {
      case Phase.AwaitingDiscard(_, taken) => taken.toList
      case _                               => Nil
    }

  private def eachPlayer(playing: Game.InPlay)(check: (Player, Json) => Expectations) =
    Player.values.toList.foldLeft(success) { (soFar, you) =>
      soFar and check(you, PlayerView.of(playing, you).asJson)
    }

  test("a view names no card the player is not entitled to see") {
    forall(GameGen.gamePlayed(12)) { game =>
      game match {
        case playing: Game.InPlay if playing.previous.isEmpty =>
          eachPlayer(playing) { (you, payload) =>
            val table   = playing.round.table
            val allowed =
              table.hands(you).cards ++ table.discard.headOption.toList ++
                takenFromThePile(playing.round)

            expect.eql(cardsIn(payload).diff(allowed), List.empty[Card])
          }
        case _ => success
      }
    }
  }

  test("what a view says of the round in play never names a card from the stock") {
    forall(GameGen.gamePlayed(12)) { game =>
      game match {
        case playing: Game.InPlay =>
          eachPlayer(playing) { (_, payload) =>
            expect.eql(
              aboutTheRoundInPlay(payload).intersect(playing.round.table.stock),
              List.empty[Card]
            )
          }
        case _ => success
      }
    }
  }

  test("a view says how many cards the other player holds and never which") {
    forall(GameGen.gamePlayed(12)) { game =>
      game match {
        case playing: Game.InPlay =>
          eachPlayer(playing) { (you, payload) =>
            val theirs = playing.round.table.hands(you.other).cards
            val public = takenFromThePile(playing.round)

            expect.eql(
              aboutTheRoundInPlay(payload).intersect(theirs).diff(public),
              List.empty[Card]
            ) and
              expect.eql(payload.hcursor.get[Int]("opponentSize"), Right(theirs.size))
          }
        case _ => success
      }
    }
  }

  test("the payload carries the size of the stock and never the stock") {
    forall(GameGen.gamePlayed(12)) { game =>
      game match {
        case playing: Game.InPlay =>
          eachPlayer(playing) { (_, payload) =>
            expect(!payload.noSpaces.contains("\"stock\":[")) and
              expect.eql(
                payload.hcursor.get[Int]("stockSize"),
                Right(playing.round.table.stock.size)
              )
          }
        case _ => success
      }
    }
  }
}
