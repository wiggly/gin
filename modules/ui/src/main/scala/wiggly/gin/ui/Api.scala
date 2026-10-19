package wiggly.gin.ui

import cats.effect.IO
import io.circe.syntax.*
import io.circe.{Decoder as JsonDecoder, parser}
import tyrian.Cmd
import tyrian.http.*
import wiggly.gin.core.domain.{Move, PlayerName, PlayerView}
import wiggly.gin.core.port.Credentials
import wiggly.gin.protocol.Codecs.given
import wiggly.gin.protocol.Introduction

/** Every call the browser makes.
  *
  * The UI reaches the server through the public routes and nothing else, so a UI that plays a game
  * is evidence that the API can be played through.
  */
object Api {

  private val games = "/api/v1/games"

  def create(name: PlayerName): Cmd[IO, Msg] =
    Http.send(Request.post(games, introducing(name)), reading[Credentials](Msg.Took.apply))

  def join(id: String, name: PlayerName): Cmd[IO, Msg] =
    Http.send(
      Request.post(s"$games/$id/join", introducing(name)),
      reading[Credentials](Msg.Took.apply)
    )

  private def introducing(name: PlayerName): Body = Body.json(Introduction(name).asJson.noSpaces)

  def play(seat: Credentials, move: Move): Cmd[IO, Msg] = {
    val request = Request[Msg](
      method = Method.Post,
      headers = List(Header("Authorization", s"Bearer ${seat.token.value}")),
      url = s"$games/${seat.id.value}/moves",
      body = Body.json(move.asJson.noSpaces),
      timeout = Request.DefaultTimeOut,
      credentials = RequestCredentials.SameOrigin,
      cache = RequestCache.Default
    )

    Http.send(request, reading[PlayerView](Msg.Saw.apply))
  }

  /** A reply becomes a message whichever way it went.
    *
    * A refusal carries the server's own `{"error": ...}` body, so the reason the player is shown
    * is the name the rules gave it rather than a decoding failure against a shape that was never
    * going to be there.
    */
  private def reading[A: JsonDecoder](onSuccess: A => Msg): Decoder[Msg] =
    Decoder[Msg](
      onResponse = response =>
        if (response.status.code >= 200 && response.status.code < 300) {
          parser.decode[A](response.body).fold(error => Msg.Failed(error.getMessage), onSuccess)
        } else {
          Msg.Failed(refusal(response))
        },
      onError = error => Msg.Failed(error.toString)
    )

  private def refusal(response: Response): String =
    parser
      .parse(response.body)
      .flatMap(_.hcursor.get[String]("error"))
      .getOrElse(s"the server answered ${response.status.code}")
}
