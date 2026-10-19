package wiggly.gin.server.adapter.http

import cats.effect.Temporal
import fs2.Stream
import io.circe.Encoder
import io.circe.syntax.*
import org.http4s.ServerSentEvent

import scala.concurrent.duration.FiniteDuration

/** Values as server-sent events, one whole value to an event.
  *
  * The comments are there because an idle connection through a proxy is dropped, and a dropped
  * connection looks to a player like a game or a lobby that stopped responding.
  */
object EventStreams {

  def apply[F[_]: Temporal, A: Encoder](
      values: Stream[F, A],
      heartbeat: FiniteDuration
  ): Stream[F, ServerSentEvent] = {
    val events = values.map(value => ServerSentEvent(data = Some(value.asJson.noSpaces)))
    val beats  = Stream
      .awakeEvery[F](heartbeat)
      .as(ServerSentEvent(data = None, comment = Some("still here")))

    events.merge(beats)
  }
}
