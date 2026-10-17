package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.domain.*

/** A round in play as one player sees it. */
object ViewGen {

  def viewOf(game: Game.InPlay, you: Player): PlayerView.InPlay = {
    PlayerView.of(game, you) match {
      case view: PlayerView.InPlay => view
      case other                   => sys.error(s"a game in play was seen as $other")
    }
  }

  def held(view: PlayerView.InPlay): List[Card] =
    view.arrangement.melds.flatMap(_.cards.toList) ++ view.arrangement.deadwood

  val inPlay: Gen[PlayerView.InPlay] = for {
    game <- GameGen.inPlay
    you  <- Gen.oneOf(Player.values.toList)
  } yield viewOf(game, you)

  /** Built rather than filtered. A random walk owes a discard about one step in three, and a
    * filter that threw the rest away would starve the properties that need a card to choose.
    */
  val discarding: Gen[PlayerView.InPlay] = for {
    game <- GameGen.inPlay
    next <- GameGen.deck
  } yield {
    val drawn = game.round.phase match {
      case _: Phase.AwaitingDiscard => game
      case _                        => drawFrom(game, next)
    }

    viewOf(drawn, drawn.round.onTurn)
  }

  /** The mover may be either player, so the viewer sees their own move or the other player's. */
  val turn: Gen[(PlayerView.InPlay, PlayerView.InPlay)] = for {
    game <- GameGen.inPlay
    you  <- Gen.oneOf(Player.values.toList)
    move <- Gen.oneOf(Moves.available(viewOf(game, game.round.onTurn)))
    next <- GameGen.deck
  } yield {
    val later = after(game, move, next)

    (viewOf(game, you), viewOf(later, you))
  }

  /** A discard or knock by the player who owes one, seen by that same player before and after. */
  val discardTurn: Gen[(PlayerView.InPlay, PlayerView.InPlay)] = for {
    game     <- GameGen.inPlay
    drawDeck <- GameGen.deck
    next     <- GameGen.deck
    drawn = game.round.phase match {
      case _: Phase.AwaitingDiscard => game
      case _                        => drawFrom(game, drawDeck)
    }
    you = drawn.round.onTurn
    move <- Gen.oneOf(Moves.available(viewOf(drawn, you)).filter {
      case _: Move.Discard | _: Move.Knock => true
      case _                               => false
    })
  } yield (viewOf(drawn, you), viewOf(after(drawn, move, next), you))

  private def after(game: Game.InPlay, move: Move, next: Deck): Game.InPlay = {
    game.played(game.round.onTurn, move, next) match {
      case Right(playing: Game.InPlay) => playing
      // The match ended, so there is no later round to see. The same view twice is still a turn.
      case Right(_)    => game
      case Left(fault) => sys.error(s"a legal move was refused: $move, $fault")
    }
  }

  private def drawFrom(game: Game.InPlay, next: Deck): Game.InPlay = {
    val onTurn = game.round.onTurn
    val draw   = Moves.available(viewOf(game, onTurn)).collectFirst {
      case move @ (Move.DrawStock | Move.DrawDiscard) => move
    }

    draw.flatMap(game.played(onTurn, _, next).toOption) match {
      case Some(drawn: Game.InPlay) => drawn
      case other                    => sys.error(s"drawing did not leave a discard owed: $other")
    }
  }
}
