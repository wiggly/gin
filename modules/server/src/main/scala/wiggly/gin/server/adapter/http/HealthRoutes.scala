package wiggly.gin.server.adapter.http

import cats.effect.Concurrent
import io.circe.Json
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.dsl.Http4sDsl

/** Liveness endpoint, for load balancers and container orchestrators.
  *
  * A response at all means the process is alive and its HTTP stack is serving, which is as much as
  * a liveness probe should assert. Readiness of downstream dependencies belongs in a separate
  * endpoint, once there are any.
  */
object HealthRoutes {

  def apply[F[_]: Concurrent]: HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl.*

    HttpRoutes.of[F] { case GET -> Root / "health" =>
      Ok(Json.obj("status" -> Json.fromString("ok")))
    }
  }
}
