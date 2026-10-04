package wiggly.gin.ui

import cats.effect.IO
import io.circe.parser
import org.scalajs.dom
import tyrian.Sub
import wiggly.gin.core.domain.PlayerView
import wiggly.gin.core.port.Credentials
import wiggly.gin.protocol.Codecs.given

/** The server's stream of views, as a subscription.
  *
  * Tyrian ships a subscription for WebSockets and none for server-sent events, so this is the one
  * facade the browser needs. The token goes in the query rather than a header, because
  * `EventSource` cannot set one, and the route already accepts it there.
  */
object Events {

  def watch(seat: Credentials): Sub[IO, Msg] = {
    val url = s"/api/v1/games/${seat.id.value}/events?token=${seat.token.value}"

    Sub.make[IO, dom.MessageEvent, Msg, dom.EventSource](s"events-${seat.id.value}")(
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
      Some(read(event.data.toString))
    )
  }

  private def read(payload: String): Msg =
    parser.decode[PlayerView](payload).fold(error => Msg.Failed(error.getMessage), Msg.Saw.apply)
}
