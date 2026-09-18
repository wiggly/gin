package wiggly.gin.core.port

import wiggly.gin.core.domain.{Game, GameFault, GameId}

/** Where games are kept.
  *
  * `update` takes a change rather than a game, so that reading, changing and writing are one step
  * the store can make indivisible. Two moves arriving together then queue behind each other
  * instead of one overwriting the other, and a caller that reads and then writes cannot be
  * written by mistake. An id the store does not hold is `NoSuchGame`.
  *
  * The change is a pure function for the same reason. Anything a change needs from the outside,
  * such as the deck for a round it might deal, has to be gathered before the update starts.
  */
trait GameRepository[F[_]] {

  def create(game: Game): F[Unit]

  def read(id: GameId): F[Option[Game]]

  def update(id: GameId)(change: Game => Either[GameFault, Game]): F[Either[GameFault, Game]]
}
