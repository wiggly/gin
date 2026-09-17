package wiggly.gin.server.adapter.http

import cats.effect.IO
import io.circe.Json
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.jsonDecoder
import org.http4s.dsl.io.*
import org.http4s.implicits.uri
import org.http4s.{HttpRoutes, Method, Request, Status, Uri}
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object GinApiSuite extends SimpleIOSuite with Checkers {

  private val gameRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "ping" =>
    Ok(Json.obj("pong" -> Json.True))
  }

  private val httpApp = GinApi.httpApp[IO](gameRoutes)

  test("health is served from the root, outside the versioned API") {
    httpApp.run(Request[IO](Method.GET, uri"/health")).map { response =>
      expect.eql(response.status, Status.Ok)
    }
  }

  test("game routes are mounted under the versioned API prefix") {
    httpApp.run(Request[IO](Method.GET, uri"/api/v1/ping")).flatMap { response =>
      response.as[Json].map { body =>
        expect.eql(response.status, Status.Ok) and
          expect.eql(body, Json.obj("pong" -> Json.True))
      }
    }
  }

  test("game routes are not reachable without the prefix") {
    httpApp.run(Request[IO](Method.GET, uri"/ping")).map { response =>
      expect.eql(response.status, Status.NotFound)
    }
  }

  test("every unknown path answers 404 with a JSON body a client can parse") {
    // Path segments are deliberately drawn from a small alphabet: weaver's checkers do not
    // shrink, so a counterexample is reported exactly as generated and wants to stay readable.
    val segment = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)
    val unknown = Gen.nonEmptyListOf(segment).map(_.mkString("/api/v1/", "/", ""))

    forall(unknown.suchThat(_ != "/api/v1/ping")) { path =>
      val request = Request[IO](Method.GET, Uri.unsafeFromString(path))

      httpApp.run(request).flatMap { response =>
        response.as[Json].map { body =>
          expect.eql(response.status, Status.NotFound) and
            expect.eql(body, Json.obj("error" -> Json.fromString("Not Found")))
        }
      }
    }
  }

  test("a failing route answers with a JSON body instead of leaking the exception") {
    val boom: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "boom" =>
      IO.raiseError(new RuntimeException("database on fire, password=hunter2"))
    }

    val request = Request[IO](Method.GET, uri"/api/v1/boom")

    GinApi.httpApp[IO](boom).run(request).flatMap { response =>
      response.as[Json].map { body =>
        expect.eql(response.status, Status.InternalServerError) and
          expect.eql(body, Json.obj("error" -> Json.fromString("Internal Server Error")))
      }
    }
  }

  test("the API answers even when no game routes are wired in yet") {
    GinApi
      .httpApp[IO](HttpRoutes.empty[IO])
      .run(Request[IO](Method.GET, uri"/health"))
      .map(response => expect.eql(response.status, Status.Ok))
  }
}
