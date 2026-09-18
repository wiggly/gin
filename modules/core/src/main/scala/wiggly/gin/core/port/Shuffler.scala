package wiggly.gin.core.port

import wiggly.gin.core.domain.Deck

/** A deck in an order nobody chose.
  *
  * A shuffle is an effect, which is why the rules of the game never perform one: `deal` is handed
  * a deck that is already shuffled, and this is where that deck comes from.
  */
trait Shuffler[F[_]] {
  def shuffled: F[Deck]
}
