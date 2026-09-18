package wiggly.gin.fake

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import fs2.Stream
import wiggly.gin.core.domain.{Game, GameId}
import wiggly.gin.core.port.GameEvents

/** A broker that remembers what was published rather than delivering it live.
  *
  * `watch` replays what a game has been through, which is enough to check that the service turns
  * states into that player's views. Whether a live subscriber misses anything is a question about
  * the real adapter and is answered in its own suite.
  */
final class FakeEvents[F[_]](state: Ref[F, List[Game]]) extends GameEvents[F] {

  def publish(game: Game): F[Unit] = state.update(_ :+ game)

  def watch(id: GameId): Stream[F, Game] =
    Stream.eval(state.get).flatMap(games => Stream.emits(games.filter(_.id === id)))

  def published: F[List[Game]] = state.get
}

object FakeEvents {
  def apply[F[_]: Sync]: F[FakeEvents[F]] = Ref.of(List.empty[Game]).map(new FakeEvents(_))
}
