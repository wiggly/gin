package wiggly.gin.server.adapter.memory

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import wiggly.gin.core.domain.{Game, GameFault, GameId}
import wiggly.gin.core.port.GameRepository

/** Games in a `Ref`, which lasts as long as the process does.
  *
  * `update` is one `modify`, which is the whole reason the port asks for a change rather than a
  * game: reading, applying and writing happen with nothing in between, so two moves that arrive
  * together queue instead of one landing on top of the other.
  */
object MemoryGameRepository {

  def apply[F[_]: Sync]: F[GameRepository[F]] =
    Ref.of[F, Map[GameId, Game]](Map.empty).map(from)

  private def from[F[_]: Sync](games: Ref[F, Map[GameId, Game]]): GameRepository[F] = {
    new GameRepository[F] {

      def create(game: Game): F[Unit] = games.update(_.updated(game.id, game))

      def read(id: GameId): F[Option[Game]] = games.get.map(_.get(id))

      def update(id: GameId)(
          change: Game => Either[GameFault, Game]
      ): F[Either[GameFault, Game]] =
        games.modify { stored =>
          stored.get(id).toRight(GameFault.NoSuchGame).flatMap(change) match {
            case Right(game) => (stored.updated(id, game), Right(game))
            case Left(fault) => (stored, Left(fault))
          }
        }
    }
  }
}
