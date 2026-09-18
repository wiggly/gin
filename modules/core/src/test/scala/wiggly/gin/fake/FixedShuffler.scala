package wiggly.gin.fake

import cats.Applicative
import cats.syntax.all.*
import wiggly.gin.core.domain.Deck
import wiggly.gin.core.port.Shuffler

/** A shuffle that is not one, so that a game played through the service deals the cards the test
  * chose and is as predictable as a round played through `GameState`.
  */
final class FixedShuffler[F[_]: Applicative](deck: Deck) extends Shuffler[F] {
  def shuffled: F[Deck] = deck.pure[F]
}
