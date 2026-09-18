package wiggly.gin.server.adapter.http

import cats.effect.Temporal
import cats.syntax.all.*
import fs2.Stream
import io.circe.syntax.*
import io.circe.{Encoder, Json}
import org.http4s.circe.CirceEntityDecoder.circeEntityDecoder
import org.http4s.circe.CirceEntityEncoder.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Authorization, `WWW-Authenticate`}
import org.http4s.{
  AuthScheme,
  Challenge,
  Credentials as HttpCredentials,
  HttpRoutes,
  Request,
  Response,
  ServerSentEvent,
  Status
}
import wiggly.gin.core.domain.{GameFault, GameId, Move, PlayerView, Token}
import wiggly.gin.core.port.GameService

import scala.concurrent.duration.FiniteDuration

import Codecs.given

/** The routes a client plays through.
  *
  * The adapter knows how to speak HTTP and nothing else. It turns a path into an id, a header into
  * a token and a body into a move, hands all three to the service, and turns what comes back into
  * a status. Every refusal the service can give has exactly one status here, and no rule of the
  * game is restated on the way.
  */
object GameRoutes {

  def apply[F[_]: Temporal](service: GameService[F], heartbeat: FiniteDuration): HttpRoutes[F] = {
    val dsl = Http4sDsl[F]
    import dsl.*

    /** Every response a client can be given, including the refusals, carries a JSON body, so that
      * one parser handles all of them. A refusal says which rule or which refusal it was and
      * nothing more.
      */
    def refused(fault: GameFault): F[Response[F]] = {
      val body = Json.obj("error" -> Json.fromString(Codecs.reason(fault)))

      fault match {
        case GameFault.NoSuchGame  => NotFound(body)
        case GameFault.NotAPlayer  => Forbidden(body)
        case GameFault.AlreadyFull => Conflict(body)
        case GameFault.NotInPlay   => Conflict(body)
        case GameFault.Illegal(_)  => UnprocessableContent(body)
      }
    }

    def answered[A: Encoder](result: Either[GameFault, A]): F[Response[F]] =
      result.fold(refused, value => Ok(value.asJson))

    /** A token is the whole of a player's claim to a seat, so a request without one is answered
      * before the game is looked up at all.
      */
    def withToken(request: Request[F])(seated: Token => F[Response[F]]): F[Response[F]] =
      tokenOf(request) match {
        case Some(token) => seated(token)
        case None        =>
          Response[F](Status.Unauthorized)
            .putHeaders(`WWW-Authenticate`(Challenge("Bearer", "gin")))
            .withEntity(Json.obj("error" -> Json.fromString("unauthorized")))
            .pure[F]
      }

    /** The stream a watcher holds open: that player's view of the game as it stands, then one for
      * every state it reaches afterwards.
      *
      * The comments are there because an idle connection through a proxy is dropped, and a
      * dropped connection looks to a player like a game that stopped responding.
      */
    def watching(views: Stream[F, PlayerView]): Stream[F, ServerSentEvent] = {
      val states = views.map(view => ServerSentEvent(data = Some(view.asJson.noSpaces)))
      val beats  = Stream
        .awakeEvery[F](heartbeat)
        .as(ServerSentEvent(data = None, comment = Some("still here")))

      states.merge(beats)
    }

    HttpRoutes.of[F] {
      case POST -> Root / "games" =>
        service.create.flatMap(credentials => Created(credentials.asJson))

      case POST -> Root / "games" / id / "join" =>
        service.join(GameId(id)).flatMap(answered)

      case request @ GET -> Root / "games" / id =>
        withToken(request)(token => service.look(GameId(id), token).flatMap(answered))

      case request @ POST -> Root / "games" / id / "moves" =>
        withToken(request) { token =>
          request.attemptAs[Move].value.flatMap {
            case Right(move) => service.play(GameId(id), token, move).flatMap(answered)
            case Left(_)     =>
              BadRequest(Json.obj("error" -> Json.fromString("malformed-move")))
          }
        }

      case request @ GET -> Root / "games" / id / "events" =>
        withToken(request) { token =>
          service.watch(GameId(id), token).flatMap {
            case Right(views) => Ok(watching(views))
            case Left(fault)  => refused(fault)
          }
        }
    }
  }

  /** The bearer token, or the one in the query string.
    *
    * The query is there for the event stream alone: a browser's `EventSource` cannot set a header,
    * so a stream that only took the header could not be watched from a browser at all. It is the
    * weaker of the two places, because a query string reaches logs and proxy history, which is one
    * reason a token is withdrawable rather than signed.
    */
  private def tokenOf[F[_]](request: Request[F]): Option[Token] =
    request.headers
      .get[Authorization]
      .collect { case Authorization(HttpCredentials.Token(AuthScheme.Bearer, token)) =>
        Token(token)
      }
      .orElse(request.uri.query.params.get("token").map(Token.apply))
}
