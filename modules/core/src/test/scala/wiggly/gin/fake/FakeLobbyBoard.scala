package wiggly.gin.fake

import cats.data.NonEmptyList
import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import fs2.Stream
import wiggly.gin.core.domain.{Listing, Lobby}
import wiggly.gin.core.port.LobbyBoard

/** A board that keeps every lobby it has held, so that a test can ask whether a game was listed
  * at any point and not only at the end. `watch` reads the latest once and does not stay open.
  */
final class FakeLobbyBoard[F[_]](state: Ref[F, NonEmptyList[Lobby]]) extends LobbyBoard[F] {

  def change(f: Lobby => Lobby): F[Unit] = state.update(held => held :+ f(held.last))

  def watch: Stream[F, List[Listing]] = Stream.eval(state.get).map(_.last.listings)

  def history: F[NonEmptyList[Lobby]] = state.get
}

object FakeLobbyBoard {
  def apply[F[_]: Sync]: F[FakeLobbyBoard[F]] =
    Ref.of(NonEmptyList.one(Lobby.empty)).map(new FakeLobbyBoard(_))
}
