package wiggly.gin.server.adapter.memory

import cats.effect.Concurrent
import cats.effect.std.AtomicCell
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import wiggly.gin.core.domain.{Game, GameId}
import wiggly.gin.core.port.GameEvents

/** A signal per game, which watchers read the latest of.
  *
  * A signal rather than a queue, because an event is a whole game: a watcher that falls behind
  * wants the state as it now is and not the states it missed. That is also what keeps a slow
  * client from costing the player who moved anything, since nothing has to be buffered for it and
  * nobody waits for it to catch up.
  *
  * `discrete` starts with the value the signal holds, so a watcher is handed the game as it stands
  * and then everything after, with no window between the two.
  *
  * A signal is never removed. One per game played is a map entry and a cell rather than something
  * that grows on its own, and there is nowhere to record that a game is finished with until a
  * store outlives the process.
  */
object MemoryGameEvents {

  def apply[F[_]: Concurrent]: F[GameEvents[F]] =
    AtomicCell[F].of(Map.empty[GameId, SignallingRef[F, Option[Game]]]).map(from)

  private def from[F[_]: Concurrent](
      signals: AtomicCell[F, Map[GameId, SignallingRef[F, Option[Game]]]]
  ): GameEvents[F] = {
    new GameEvents[F] {

      def publish(game: Game): F[Unit] = signal(game.id).flatMap(_.set(Some(game)))

      def watch(id: GameId): Stream[F, Game] =
        Stream.eval(signal(id)).flatMap(_.discrete.unNone)

      /** The signal for this game, made if this is the first anybody has heard of it. The cell
        * settles the race, so two callers arriving together share one signal rather than watching
        * different ones.
        */
      private def signal(id: GameId): F[SignallingRef[F, Option[Game]]] =
        signals.evalModify { known =>
          known.get(id) match {
            case Some(found) => (known, found).pure[F]
            case None        =>
              SignallingRef
                .of[F, Option[Game]](None)
                .map(fresh => (known.updated(id, fresh), fresh))
          }
        }
    }
  }
}
