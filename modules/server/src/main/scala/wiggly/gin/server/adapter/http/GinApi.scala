package wiggly.gin.server.adapter.http

import cats.data.Kleisli
import cats.effect.Async
import cats.syntax.applicativeError.*
import cats.syntax.semigroupk.*
import io.circe.Json
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.server.Router
import org.http4s.server.middleware.EntityLimiter
import org.http4s.{HttpApp, HttpRoutes, Request, Response, Status}

/** Assembles the routes clients talk to into a single application.
  *
  * The routes under the API prefix, the game's and the lobby's, are passed in rather than constructed here: this is a driving adapter, so
  * it knows how to speak HTTP and nothing about how a hand of gin is played.
  */
object GinApi {

  /** Large enough for any body the API accepts, which is a name and an id, and small enough that an
    * anonymous caller cannot make the server buffer a megabyte to decode it.
    */
  val BodyLimit: Long = 4L * 1024

  /** Everything the game exposes lives under this prefix, so that a later revision of the API can
    * be served alongside this one rather than breaking clients already in the wild.
    */
  val ApiPrefix: String = "/api/v1"

  /** The order carries two rules, so it is stated here rather than left to whichever route
    * happens to match first. The API and the health route both answer before the browser
    * application does, because that application answers every path it is given.
    */
  def httpApp[F[_]: Async](apiRoutes: HttpRoutes[F]): HttpApp[F] = {
    val routes = Router(
      ApiPrefix -> EntityLimiter.httpRoutes(apiRoutes, BodyLimit),
      "/"       -> (HealthRoutes[F] <+> WebRoutes[F])
    )

    jsonErrors(routes)
  }

  /** Gives clients a JSON body for the responses http4s would otherwise answer with an empty one,
    * so that a client can parse every response the same way. The failure case deliberately says
    * nothing beyond the status: the detail goes to the logs, not over the wire.
    */
  private def jsonErrors[F[_]: Async](routes: HttpRoutes[F]): HttpApp[F] =
    Kleisli { (request: Request[F]) =>
      routes
        .run(request)
        .getOrElse(errorResponse(Status.NotFound))
        .handleError {
          case _: EntityLimiter.EntityTooLarge => errorResponse(Status.PayloadTooLarge)
          case _                               => errorResponse(Status.InternalServerError)
        }
    }

  private def errorResponse[F[_]](status: Status): Response[F] =
    Response[F](status).withEntity(Json.obj("error" -> Json.fromString(status.reason)))
}
