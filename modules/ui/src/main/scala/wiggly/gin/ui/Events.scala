package wiggly.gin.ui

import cats.effect.IO
import io.circe.{Decoder, parser}
import org.scalajs.dom
import tyrian.Sub
import wiggly.gin.core.domain.{Listing, PlayerView}
import wiggly.gin.core.port.Credentials
import wiggly.gin.protocol.Codecs.given

/** The server's stream of views, as a subscription.
  *
  * Tyrian ships a subscription for WebSockets and none for server-sent events, so this is the one
  * facade the browser needs. The token goes in the query rather than a header, because
  * `EventSource` cannot set one, and the route already accepts it there. The lobby stream needs no
  * token.
  */
object Events {

  def watch(seat: Credentials): Sub[IO, Msg] =
    stream[PlayerView](
      s"events-${seat.id.value}",
      s"/api/v1/games/${seat.id.value}/events?token=${seat.token.value}"
    )(Msg.Saw.apply)

  val lobby: Sub[IO, Msg] = stream[List[Listing]]("lobby", "/api/v1/lobby/events")(Msg.Listed.apply)

  private def stream[A: Decoder](key: String, url: String)(seen: A => Msg): Sub[IO, Msg] = {
    Sub.make[IO, dom.MessageEvent, Msg, dom.EventSource](key)(
      (emit: Either[Throwable, dom.MessageEvent] => Unit) =>
        IO {
          val source = new dom.EventSource(url)
          source.onmessage = (event: dom.MessageEvent) => emit(Right(event))
          // A browser reconnects a dropped EventSource on its own. Reporting the drop would show
          // the player a failure that has already fixed itself.
          source.onerror = (_: dom.Event) => ()
          source
        }
    )((source: dom.EventSource) => IO(source.close()))((event: dom.MessageEvent) =>
      Some(parser.decode[A](event.data.toString).fold(error => Msg.Failed(error.getMessage), seen))
    )
  }
}
