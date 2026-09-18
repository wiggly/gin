package wiggly.gin.fake

import cats.Functor
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import wiggly.gin.core.domain.{Game, GameFault, GameId}
import wiggly.gin.core.port.GameRepository

/** A store in a `Ref`, which is all the service asks of one.
  *
  * It makes no attempt at the atomicity the real adapter has to have. Nothing here applies two
  * changes at once, and the adapter's own suite is where that is proved.
  */
final class FakeRepository[F[_]: Functor](state: Ref[F, Map[GameId, Game]])
    extends GameRepository[F] {

  def create(game: Game): F[Unit] = state.update(_.updated(game.id, game))

  def read(id: GameId): F[Option[Game]] = state.get.map(_.get(id))

  def update(id: GameId)(change: Game => Either[GameFault, Game]): F[Either[GameFault, Game]] =
    state.modify { games =>
      games.get(id).toRight(GameFault.NoSuchGame).flatMap(change) match {
        case Right(game) => (games.updated(id, game), Right(game))
        case Left(fault) => (games, Left(fault))
      }
    }

  def stored: F[Map[GameId, Game]] = state.get
}

object FakeRepository {
  def apply[F[_]: Sync]: F[FakeRepository[F]] =
    Ref.of(Map.empty[GameId, Game]).map(new FakeRepository(_))
}
