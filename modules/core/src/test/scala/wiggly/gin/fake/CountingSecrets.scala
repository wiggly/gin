package wiggly.gin.fake

import cats.Functor
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import wiggly.gin.core.domain.{GameId, Token}
import wiggly.gin.core.port.Secrets

/** Identifiers that count up instead of being unguessable, so that a test can name the game it
  * just created. Being guessable is the point here and the whole of why it is not the adapter.
  */
final class CountingSecrets[F[_]: Functor](counter: Ref[F, Int]) extends Secrets[F] {

  def gameId: F[GameId] = next.map(count => GameId(s"game-$count"))

  def token: F[Token] = next.map(count => Token(s"token-$count"))

  private def next: F[Int] = counter.updateAndGet(_ + 1)
}

object CountingSecrets {
  def apply[F[_]: Sync]: F[CountingSecrets[F]] = Ref.of(0).map(new CountingSecrets(_))
}
