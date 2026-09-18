package wiggly.gin.core.port

import wiggly.gin.core.domain.{GameId, Token}

/** Identifiers nobody can guess.
  *
  * A token is the whole of a player's claim to a seat, so one that can be guessed is not a token
  * at all. A game id is in a URL and is not a claim to anything, but it is the only way to reach a
  * game, so it is not worth making it easier to find than the token is.
  */
trait Secrets[F[_]] {

  def gameId: F[GameId]

  def token: F[Token]
}
