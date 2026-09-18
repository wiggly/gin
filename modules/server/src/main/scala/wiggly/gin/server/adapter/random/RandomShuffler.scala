package wiggly.gin.server.adapter.random

import cats.MonadThrow
import cats.effect.Sync
import cats.effect.std.Random
import cats.syntax.all.*
import wiggly.gin.core.domain.Deck
import wiggly.gin.core.port.Shuffler

/** The deck in an order drawn from an ordinary random source.
  *
  * Ordinary rather than secure: a shuffle has to be unrepeatable, and a player who could predict
  * one would have to be able to see the stock as well, which is what the redaction is for.
  */
object RandomShuffler {

  def apply[F[_]: Sync]: F[Shuffler[F]] = Random.scalaUtilRandom[F].map(from)

  private def from[F[_]: MonadThrow](random: Random[F]): Shuffler[F] = {
    new Shuffler[F] {

      /** A shuffle of the 52 cards is a deck, so the refusal below cannot happen. It is here
        * because `Deck.from` is the only door into the type and an adapter is allowed to fail,
        * which is better than a shuffle quietly producing something that is not a deck.
        */
      def shuffled: F[Deck] =
        random
          .shuffleList(Deck.ordered.cards)
          .flatMap { cards =>
            Deck
              .from(cards)
              .liftTo[F](new IllegalStateException("a shuffle of the deck was not a deck"))
          }
    }
  }
}
