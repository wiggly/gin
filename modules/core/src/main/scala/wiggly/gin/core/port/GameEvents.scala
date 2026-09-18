package wiggly.gin.core.port

import fs2.Stream
import wiggly.gin.core.domain.{Game, GameId}

/** How a player finds out that the other one moved.
  *
  * A watcher is handed the game as it stands and then every state it reaches afterwards, so there
  * is no window between asking and listening in which a move could be missed.
  *
  * Every event is a whole game rather than a description of what changed. That is what lets a
  * watcher who falls behind be brought up to date with the latest state instead of being fed a
  * backlog it has to replay, and it is why a slow client costs the mover nothing.
  */
trait GameEvents[F[_]] {

  def publish(game: Game): F[Unit]

  def watch(id: GameId): Stream[F, Game]
}
