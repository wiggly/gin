package wiggly.gin.ui

import org.scalajs.dom
import wiggly.gin.core.domain.{GameId, Player, Token}
import wiggly.gin.core.port.Credentials

/** Where a player's claim to a seat is kept between page loads.
  *
  * A refresh that loses the token loses the seat, and the token is the whole of a player's claim
  * to one. It is keyed by game, so opening a second game does not evict the first.
  *
  * What a seat reads and writes as is separated from the storage it is read and written to. The
  * first is where a mistake can hide and is tested; the second is two calls into the browser,
  * which no unit test can reach without a DOM to run in.
  */
object Seat {

  def key(id: GameId): String = s"wiggly-gin:${id.value}"

  def encode(seat: Credentials): String = s"${seat.you.toString}:${seat.token.value}"

  def decode(id: GameId, stored: String): Option[Credentials] =
    stored.split(":", 2) match {
      case Array(player, token) =>
        Player.values.find(_.toString == player).map(Credentials(id, _, Token(token)))
      case _ => None
    }

  /** The game a URL names, which is what makes a shared link open the right table. */
  def gameIdIn(path: String): Option[String] =
    Option(path).map(_.stripPrefix("/")).filter(_.nonEmpty)

  def remember(seat: Credentials): Unit =
    dom.window.sessionStorage.setItem(key(seat.id), encode(seat))

  def recall(id: GameId): Option[Credentials] =
    Option(dom.window.sessionStorage.getItem(key(id))).flatMap(decode(id, _))
}
