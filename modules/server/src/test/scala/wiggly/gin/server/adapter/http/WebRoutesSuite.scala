package wiggly.gin.server.adapter.http

import cats.effect.IO
import org.http4s.*
import org.http4s.implicits.*
import weaver.SimpleIOSuite

object WebRoutesSuite extends SimpleIOSuite {

  private val routes = WebRoutes[IO].orNotFound

  test("the root serves the page") {
    routes.run(Request[IO](Method.GET, uri"/")).flatMap { response =>
      response.as[String].map { body =>
        expect.same(Status.Ok, response.status) and expect(body.contains("""id="app""""))
      }
    }
  }

  test("a link to a game serves the page, so the application can open it") {
    routes.run(Request[IO](Method.GET, uri"/play/some-game-id")).map { response =>
      expect.same(Status.Ok, response.status)
    }
  }

  test("a file that exists is served as itself") {
    routes.run(Request[IO](Method.GET, uri"/gin.css")).flatMap { response =>
      response.as[String].map { body =>
        expect.same(Status.Ok, response.status) and expect(body.contains(".card"))
      }
    }
  }

  test("a path that is neither the page nor a file is declined, not answered with the page") {
    routes.run(Request[IO](Method.GET, uri"/ping")).map { response =>
      expect.same(Status.NotFound, response.status)
    }
  }

  test("a file that does not exist is not answered with the page") {
    routes.run(Request[IO](Method.GET, uri"/nothing.css")).map { response =>
      expect.same(Status.NotFound, response.status)
    }
  }
}

/** The browser application sits beside the API rather than in front of it. These are the promises
  * it must not take over.
  */
object GinApiOrderSuite extends SimpleIOSuite {

  private val app = GinApi.httpApp[IO](HttpRoutes.empty[IO])

  test("health answers before the page does") {
    app.run(Request[IO](Method.GET, uri"/health")).map { response =>
      expect.same(Status.Ok, response.status)
    }
  }

  test("an unknown api path is still a JSON refusal") {
    app.run(Request[IO](Method.GET, uri"/api/v1/nonsense")).flatMap { response =>
      response.as[String].map { body =>
        expect.same(Status.NotFound, response.status) and expect(body.contains("error"))
      }
    }
  }

  test("a link to a game gets the page") {
    app.run(Request[IO](Method.GET, uri"/play/a-game-id")).flatMap { response =>
      response.as[String].map { body =>
        expect.same(Status.Ok, response.status) and expect(body.contains("""id="app""""))
      }
    }
  }
}
