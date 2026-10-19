package wiggly.gin.core.domain

import cats.implicits.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.gen.{GameGen, NameGen}

object PlayerViewSuite extends SimpleIOSuite with Checkers {

  private val waiting = Game.AwaitingOpponent(GameId("a-game"), Token("host"), NameGen.named("Ada"))

  private def cardsIn(view: PlayerView.InPlay): List[Card] = {
    val held = view.arrangement.melds.flatMap(_.cards.toList) ++ view.arrangement.deadwood

    val taken = view.phase match {
      case Phase.AwaitingDiscard(_, taken) => taken.toList
      case _                               => Nil
    }

    held ++ view.upcard.toList ++ taken
  }

  private def inPlay(view: PlayerView): PlayerView.InPlay = {
    view match {
      case playing: PlayerView.InPlay => playing
      case other                      => sys.error(s"the view is not of a round in play: $other")
    }
  }

  pureTest("a game waiting for an opponent says only that, and whose it is") {
    expect.eql(
      PlayerView.of(waiting, Player.One),
      PlayerView.AwaitingOpponent(GameId("a-game"), Player.One, NameGen.named("Ada"))
    )
  }

  test("every view of a round in play carries both names unchanged") {
    forall(GameGen.gamePlayed(10)) { game =>
      game match {
        case playing: Game.InPlay =>
          Player.values.toList.foldLeft(success) { (soFar, you) =>
            soFar and expect.eql(inPlay(PlayerView.of(playing, you)).names, playing.names)
          }
        case _ => success
      }
    }
  }

  test("a player is shown their own hand and told only how many the other player holds") {
    forall(GameGen.gamePlayed(10)) { game =>
      game match {
        case playing: Game.InPlay =>
          Player.values.toList.foldLeft(success) { (soFar, you) =>
            val view  = inPlay(PlayerView.of(playing, you))
            val table = playing.round.table

            soFar and
              expect.eql(
                (view.arrangement.melds
                  .flatMap(_.cards.toList) ++ view.arrangement.deadwood).sorted,
                table.hands(you).cards
              ) and
              expect.eql(view.opponentSize, table.hands(you.other).size) and
              expect.eql(view.stockSize, table.stock.size) and
              expect.eql(view.discardSize, table.discard.size) and
              expect.eql(view.upcard, table.discard.headOption)
          }
        case _ => success
      }
    }
  }

  test("a view of a round in play names no card from the stock or the other hand") {
    forall(GameGen.gamePlayed(10)) { game =>
      game match {
        case playing: Game.InPlay if playing.previous.isEmpty =>
          Player.values.toList.foldLeft(success) { (soFar, you) =>
            val view  = inPlay(PlayerView.of(playing, you))
            val table = playing.round.table
            val named = cardsIn(view)

            val taken = view.phase match {
              case Phase.AwaitingDiscard(_, taken) => taken.toList
              case _                               => Nil
            }

            soFar and
              expect.eql(named.intersect(table.stock), List.empty[Card]) and
              expect.eql(
                named.intersect(table.hands(you.other).cards).diff(taken),
                List.empty[Card]
              )
          }
        case _ => success
      }
    }
  }

  test("the same game reads differently to each player") {
    forall(GameGen.gamePlayed(10)) { game =>
      game match {
        case playing: Game.InPlay =>
          val one = inPlay(PlayerView.of(playing, Player.One))
          val two = inPlay(PlayerView.of(playing, Player.Two))

          expect.eql(one.you, Player.One) and
            expect.eql(two.you, Player.Two) and
            expect(one.arrangement != two.arrangement) and
            expect.eql(one.onTurn, two.onTurn) and
            expect.eql(one.dealer, two.dealer)
        case _ => success
      }
    }
  }
}
