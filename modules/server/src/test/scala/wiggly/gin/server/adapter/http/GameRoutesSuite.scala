package wiggly.gin.server.adapter.http

import cats.effect.IO
import cats.implicits.*
import io.circe.Json
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.circe.jsonDecoder
import org.http4s.headers.Authorization
import org.http4s.implicits.uri
import org.http4s.{
  AuthScheme,
  Credentials as HttpCredentials,
  HttpApp,
  Method,
  Request,
  Status,
  Uri
}
import weaver.SimpleIOSuite
import wiggly.gin.core.domain.*
import wiggly.gin.core.port.{Credentials, GameService}
import wiggly.gin.core.service.Games
import wiggly.gin.fake.{CountingSecrets, FakeEvents, FakeRepository, FixedShuffler}

import scala.concurrent.duration.*

object GameRoutesSuite extends SimpleIOSuite {

  private final case class Table(app: HttpApp[IO], service: GameService[IO])

  private def table: IO[Table] = for {
    repository <- FakeRepository[IO]
    events     <- FakeEvents[IO]
    secrets    <- CountingSecrets[IO]
    service = Games[IO](repository, events, new FixedShuffler[IO](Deck.ordered), secrets)
  } yield Table(GameRoutes[IO](service, 15.seconds).orNotFound, service)

  private def at(path: String): Uri = Uri.unsafeFromString(path)

  private def bearer(token: Token): Authorization =
    Authorization(HttpCredentials.Token(AuthScheme.Bearer, token.value))

  private def request(method: Method, path: String, token: Option[Token]): Request[IO] =
    token.map(bearer).foldLeft(Request[IO](method, at(path)))(_.putHeaders(_))

  private def seated(table: Table): IO[(Credentials, Credentials)] = for {
    host  <- table.service.create
    guest <- table.service
      .join(host.id)
      .map(_.getOrElse(sys.error("a game with one player refused a second")))
  } yield (host, guest)

  test("creating a game answers 201 with the seat and the token that claims it") {
    for {
      table    <- table
      response <- table.app.run(Request[IO](Method.POST, uri"/games"))
      body     <- response.as[Json]
    } yield expect.eql(response.status, Status.Created) and
      expect.eql(body.hcursor.get[String]("you"), Right("one")) and
      expect(body.hcursor.get[String]("token").isRight) and
      expect(body.hcursor.get[String]("id").isRight)
  }

  test("joining answers 200 with the second seat") {
    for {
      table    <- table
      host     <- table.service.create
      response <- table.app.run(Request[IO](Method.POST, at(s"/games/${host.id.value}/join")))
      body     <- response.as[Json]
    } yield expect.eql(response.status, Status.Ok) and
      expect.eql(body.hcursor.get[String]("you"), Right("two"))
  }

  test("joining a game nobody created answers 404") {
    for {
      table    <- table
      response <- table.app.run(Request[IO](Method.POST, uri"/games/no-such-game/join"))
      body     <- response.as[Json]
    } yield expect.eql(response.status, Status.NotFound) and
      expect.eql(body.hcursor.get[String]("error"), Right("no-such-game"))
  }

  test("a third player answers 409") {
    for {
      table     <- table
      (host, _) <- seated(table)
      response  <- table.app.run(Request[IO](Method.POST, at(s"/games/${host.id.value}/join")))
      body      <- response.as[Json]
    } yield expect.eql(response.status, Status.Conflict) and
      expect.eql(body.hcursor.get[String]("error"), Right("already-full"))
  }

  test("looking without a token answers 401") {
    for {
      table     <- table
      (host, _) <- seated(table)
      response  <- table.app.run(request(Method.GET, s"/games/${host.id.value}", None))
    } yield expect.eql(response.status, Status.Unauthorized)
  }

  test("looking with a token neither player holds answers 403") {
    for {
      table     <- table
      (host, _) <- seated(table)
      response  <- table.app.run(
        request(Method.GET, s"/games/${host.id.value}", Some(Token("nobody")))
      )
      body <- response.as[Json]
    } yield expect.eql(response.status, Status.Forbidden) and
      expect.eql(body.hcursor.get[String]("error"), Right("not-a-player"))
  }

  test("looking with the right token answers that player's own view") {
    for {
      table         <- table
      (host, guest) <- seated(table)
      theirs <- table.app.run(request(Method.GET, s"/games/${host.id.value}", Some(host.token)))
      others <- table.app.run(request(Method.GET, s"/games/${host.id.value}", Some(guest.token)))
      first  <- theirs.as[Json]
      second <- others.as[Json]
    } yield expect.eql(theirs.status, Status.Ok) and
      expect.eql(first.hcursor.get[String]("you"), Right("one")) and
      expect.eql(second.hcursor.get[String]("you"), Right("two")) and
      expect(first.hcursor.get[Json]("arrangement") != second.hcursor.get[Json]("arrangement"))
  }

  test("a legal move answers 200 with the mover's own view") {
    for {
      table     <- table
      (host, _) <- seated(table)
      response  <- table.app.run(
        request(Method.POST, s"/games/${host.id.value}/moves", Some(host.token))
          .withEntity(Json.obj("move" -> Json.fromString("draw-discard")))
      )
      body <- response.as[Json]
    } yield expect.eql(response.status, Status.Ok) and
      expect.eql(body.hcursor.get[String]("you"), Right("one")) and
      expect.eql(body.hcursor.downField("phase").get[String]("phase"), Right("awaiting-discard"))
  }

  test("a move the rules refuse answers 422 and names the rule") {
    for {
      table         <- table
      (host, guest) <- seated(table)
      response      <- table.app.run(
        request(Method.POST, s"/games/${host.id.value}/moves", Some(guest.token))
          .withEntity(Json.obj("move" -> Json.fromString("draw-discard")))
      )
      body <- response.as[Json]
    } yield expect.eql(response.status, Status.UnprocessableContent) and
      expect.eql(body.hcursor.get[String]("error"), Right("not-your-turn"))
  }

  test("a move nobody can parse answers 400 rather than failing the request") {
    for {
      table     <- table
      (host, _) <- seated(table)
      response  <- table.app.run(
        request(Method.POST, s"/games/${host.id.value}/moves", Some(host.token))
          .withEntity(Json.obj("move" -> Json.fromString("fold")))
      )
      body <- response.as[Json]
    } yield expect.eql(response.status, Status.BadRequest) and
      expect.eql(body.hcursor.get[String]("error"), Right("malformed-move"))
  }

  test("the event stream opens with that player's own view of the game") {
    for {
      table     <- table
      (host, _) <- seated(table)
      response  <- table.app.run(
        request(Method.GET, s"/games/${host.id.value}/events", Some(host.token))
      )
      opening <- response.body
        .through(fs2.text.utf8.decode)
        .take(1)
        .compile
        .string
        .timeout(5.seconds)
    } yield expect.eql(response.status, Status.Ok) and
      expect(response.contentType.exists(_.mediaType.subType === "event-stream")) and
      expect(opening.contains("\"you\":\"one\""))
  }

  test("the event stream takes its token from the query, because a browser cannot send a header") {
    for {
      table     <- table
      (host, _) <- seated(table)
      response  <- table.app.run(
        Request[IO](Method.GET, at(s"/games/${host.id.value}/events?token=${host.token.value}"))
      )
      opening <- response.body
        .through(fs2.text.utf8.decode)
        .take(1)
        .compile
        .string
        .timeout(5.seconds)
    } yield expect.eql(response.status, Status.Ok) and expect(opening.contains("\"you\":\"one\""))
  }
}
