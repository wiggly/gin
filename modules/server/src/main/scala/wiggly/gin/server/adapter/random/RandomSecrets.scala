package wiggly.gin.server.adapter.random

import cats.Monad
import cats.effect.Sync
import cats.effect.std.SecureRandom
import cats.syntax.all.*
import wiggly.gin.core.domain.{GameId, Token}
import wiggly.gin.core.port.Secrets

/** Identifiers drawn from a cryptographically secure source.
  *
  * Secure rather than merely random because a token is the whole of a player's claim to a seat. A
  * game id is not a claim to anything, but it is the only way to reach a game, so it comes from
  * the same place rather than from anything a caller could predict.
  */
object RandomSecrets {

  /** Thirty-two alphanumeric characters, which is about 190 bits of guessing. */
  val Length: Int = 32

  def apply[F[_]: Sync]: F[Secrets[F]] = SecureRandom.javaSecuritySecureRandom[F].map(from)

  private def from[F[_]: Monad](random: SecureRandom[F]): Secrets[F] = {
    new Secrets[F] {

      def gameId: F[GameId] = opaque.map(GameId.apply)

      def token: F[Token] = opaque.map(Token.apply)

      private def opaque: F[String] =
        random.nextAlphaNumeric.replicateA(Length).map(_.mkString)
    }
  }
}
