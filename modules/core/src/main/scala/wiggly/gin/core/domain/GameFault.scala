package wiggly.gin.core.domain

import cats.{Eq, Show}

/** Why a game refused what was asked of it.
  *
  * Separate from [[GameError]], which is the rules refusing a move. These are the refusals that
  * come before the rules get a say: a game nobody has heard of, a token that seats nobody, a game
  * that is not in a position to be played. `Illegal` carries the rules' own answer rather than
  * restating it, so there is one list of reasons a move can be turned down.
  */
enum GameFault {
  case NoSuchGame
  case NotAPlayer
  case AlreadyFull
  case NotInPlay
  case Illegal(error: GameError)
}

object GameFault {
  given Eq[GameFault] = Eq.fromUniversalEquals

  given Show[GameFault] = Show.fromToString
}
