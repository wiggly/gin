package wiggly.gin.server.adapter.http

import cats.effect.IO
import io.circe.Json
import org.http4s.circe.jsonDecoder
import org.http4s.implicits.uri
import org.http4s.{Method, Request, Status}
import weaver.SimpleIOSuite

object HealthRoutesSuite extends SimpleIOSuite {

  private val routes = HealthRoutes[IO]

  test("GET /health reports the server as up") {
    val request = Request[IO](Method.GET, uri"/health")

    routes.orNotFound.run(request).flatMap { response =>
      response.as[Json].map { body =>
        expect.eql(response.status, Status.Ok) and
          expect.eql(body, Json.obj("status" -> Json.fromString("ok")))
      }
    }
  }

  test("health is only exposed at its own path") {
    val request = Request[IO](Method.GET, uri"/healthz")

    routes.orNotFound.run(request).map(response => expect.eql(response.status, Status.NotFound))
  }
}
