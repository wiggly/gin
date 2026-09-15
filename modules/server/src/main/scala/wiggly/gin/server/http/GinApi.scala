package wiggly.gin.server.http

import cats.data.Kleisli
import cats.effect.Concurrent
import cats.syntax.applicativeError.*
import io.circe.Json
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.server.Router
import org.http4s.{HttpApp, HttpRoutes, Request, Response, Status}

/** Assembles the routes clients talk to into a single application.
  *
  * The game's own routes are passed in rather than constructed here: this is a driving adapter, so
  * it knows how to speak HTTP and nothing about how a hand of gin is played.
  */
object GinApi {

  /** Everything the game exposes lives under this prefix, so that a later revision of the API can
    * be served alongside this one rather than breaking clients already in the wild.
    */
  val ApiPrefix: String = "/api/v1"

  def httpApp[F[_]: Concurrent](gameRoutes: HttpRoutes[F]): HttpApp[F] = {
    val routes = Router(
      "/"       -> HealthRoutes[F],
      ApiPrefix -> gameRoutes
    )

    jsonErrors(routes)
  }

  /** Gives clients a JSON body for the two responses http4s would otherwise answer with an empty
    * one, so that a client can parse every response the same way. The failure case deliberately
    * says nothing beyond the status: the detail goes to the logs, not over the wire.
    */
  private def jsonErrors[F[_]: Concurrent](routes: HttpRoutes[F]): HttpApp[F] =
    Kleisli { (request: Request[F]) =>
      routes
        .run(request)
        .getOrElse(errorResponse(Status.NotFound))
        .handleError(_ => errorResponse(Status.InternalServerError))
    }

  private def errorResponse[F[_]](status: Status): Response[F] =
    Response[F](status).withEntity(Json.obj("error" -> Json.fromString(status.reason)))
}
