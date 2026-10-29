package wiggly.gin.server.adapter.http

import cats.effect.Async
import org.http4s.*
import org.http4s.dsl.Http4sDsl

/** The browser application, served out of the jar.
  *
  * The application's own paths live under `/play`, rather than the whole of `/`. A fallback that
  * answered every unmatched path would answer the ones this server already promises a JSON 404
  * for, and then a client could not tell a missing endpoint from a page.
  */
object WebRoutes {

  private val Assets = "/web"

  def apply[F[_]: Async]: HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*

    HttpRoutes.of[F] {
      case request @ GET -> Root => serve(request, "index.html")

      // Every path under /play names a game the application routes, so the page answers all of
      // them and the application reads the game out of the URL for itself.
      case request @ GET -> "play" /: _ => serve(request, "index.html")

      case request @ GET -> Root / file if file.contains('.') => serve(request, file)
    }
  }

  private def serve[F[_]: Async](request: Request[F], file: String): F[Response[F]] =
    StaticFile
      .fromResource[F](s"$Assets/$file", Some(request))
      .getOrElseF(Async[F].pure(Response[F](Status.NotFound)))
}
