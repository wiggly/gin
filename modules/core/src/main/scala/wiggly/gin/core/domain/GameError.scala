package wiggly.gin.core.domain

import cats.syntax.all.*
import cats.{Eq, Show}

/** Why a move was refused.
  *
  * The states of a round carry most of the rules, so this covers what is left. A refusal returns
  * one of these and the caller still holds the state it started with, which is what keeps an
  * illegal move from leaving a round part-way through a change.
  */
enum GameError {

  /** The player is not the one the state named. */
  case NotYourTurn

  /** The player has taken no card yet this turn. */
  case MustDraw

  /** The player holds eleven cards and owes a discard. */
  case MustDiscard

  /** No upcard is on offer, so there is nothing to decline. */
  case NothingToPass

  /** The discard pile is not open: the opening draw comes from the stock. */
  case PileClosed

  /** The stock is not open: the upcard is still on offer. */
  case StockClosed

  case CardNotHeld(card: Card)

  /** A card taken from the pile cannot go straight back onto it. */
  case CannotDiscardDrawnCard(card: Card)

  /** A knock needs ten or less deadwood after the discard. */
  case CannotKnock(deadwood: Int)

  /** The round is over and accepts no move. */
  case RoundOver
}

object GameError {
  given Eq[GameError] = Eq.fromUniversalEquals

  given Show[GameError] = Show.show {
    case NotYourTurn                  => "it is not your turn"
    case MustDraw                     => "you must draw before you discard"
    case MustDiscard                  => "you must discard before your turn ends"
    case NothingToPass                => "there is no upcard on offer to pass"
    case PileClosed                   => "the discard pile is closed: draw from the stock"
    case StockClosed                  => "the stock is closed while the upcard is on offer"
    case CardNotHeld(card)            => show"you do not hold the $card"
    case CannotDiscardDrawnCard(card) => show"you have just taken the $card from the pile"
    case CannotKnock(deadwood) => s"a knock needs ten or less deadwood, and that leaves $deadwood"
    case RoundOver             => "the round is over"
  }
}
