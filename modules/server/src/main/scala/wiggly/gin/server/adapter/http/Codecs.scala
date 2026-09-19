package wiggly.gin.server.adapter.http

import io.circe.syntax.*
import io.circe.{Decoder, DecodingFailure, Encoder, Json, JsonObject}
import wiggly.gin.core.domain.*
import wiggly.gin.core.port.Credentials

/** How the game looks on the wire.
  *
  * Every case class here is derived, so a field added to one appears in the payload without
  * anybody editing this file. What is written out by hand is the handful of places where the
  * derived shape is not the shape a client should be given: a plain enum derives to `{"Ace":{}}`
  * rather than `"ace"`, a sum derives to `{"UpcardOffered":{…}}` rather than an object with a tag
  * beside its fields, and a single-field wrapper derives to `{"value":"…"}` rather than the string
  * it wraps.
  *
  * Only [[PlayerView]] and [[Credentials]] have encoders that leave the building. A `Game` and a
  * `GameState` deliberately have none: the one shape a game may be seen in is a view, and the
  * absence of a codec is what stops the other shapes reaching a client by accident.
  */
object Codecs {

  /** `CannotDiscardDrawnCard` becomes `cannot-discard-drawn-card`. One rule for every name a
    * client reads, so that none of them has to be remembered individually.
    *
    * The first pass is for a name with two capitals in a row, such as `NotAPlayer`, which the
    * second pass alone would leave as `not-aplayer`.
    */
  private def dashed(name: String): String =
    name
      .replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
      .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
      .toLowerCase

  private def named[A]: Encoder[A] = Encoder.encodeString.contramap(value => dashed(value.toString))

  private def parsed[A](values: Array[A], what: String): Decoder[A] =
    Decoder.decodeString.emap { name =>
      values.find(value => dashed(value.toString) == name).toRight(s"unknown $what: $name")
    }

  /** An object with the name of its case in front of its fields, which is how every sum in this
    * file is told apart. Prepended rather than added, so a client reads the tag first.
    */
  private def tagged(key: String, value: String, fields: JsonObject): Json =
    Json.fromJsonObject((key -> Json.fromString(value)) +: fields)

  private def tagged(key: String, value: String, fields: (String, Json)*): Json =
    Json.obj(((key -> Json.fromString(value)) +: fields)*)

  given Encoder[Rank] = named
  given Decoder[Rank] = parsed(Rank.values, "rank")

  given Encoder[Suit] = named
  given Decoder[Suit] = parsed(Suit.values, "suit")

  given Encoder[Player] = named

  /** The wrappers, as the strings they wrap. */
  given Encoder[GameId] = Encoder.encodeString.contramap(_.value)
  given Encoder[Token]  = Encoder.encodeString.contramap(_.value)

  given Encoder[Card] = Encoder.AsObject.derived
  given Decoder[Card] = Decoder.derived

  given Encoder[Tally]       = Encoder.AsObject.derived
  given Encoder[MatchResult] = Encoder.AsObject.derived
  given Encoder[Credentials] = Encoder.AsObject.derived

  given Decoder[Move] = Decoder.instance { cursor =>
    cursor.get[String]("move").flatMap {
      case "draw-stock"   => Right(Move.DrawStock)
      case "draw-discard" => Right(Move.DrawDiscard)
      case "pass"         => Right(Move.Pass)
      case "discard"      => cursor.get[Card]("card").map(Move.Discard.apply)
      case "knock"        => cursor.get[Card]("card").map(Move.Knock.apply)
      case other          => Left(DecodingFailure(s"unknown move: $other", cursor.history))
    }
  }

  given Encoder[Move] = Encoder.instance {
    case Move.DrawStock     => Json.obj("move" -> Json.fromString("draw-stock"))
    case Move.DrawDiscard   => Json.obj("move" -> Json.fromString("draw-discard"))
    case Move.Pass          => Json.obj("move" -> Json.fromString("pass"))
    case Move.Discard(card) => tagged("move", "discard", "card" -> card.asJson)
    case Move.Knock(card)   => tagged("move", "knock", "card" -> card.asJson)
  }

  given Encoder[Meld] = Encoder.instance { meld =>
    val kind = meld match {
      case _: Meld.Set => "set"
      case _: Meld.Run => "run"
    }

    tagged("kind", kind, "cards" -> meld.cards.toList.asJson)
  }

  /** Derived, and then told what the cards come to. The sum is not a field of the type, so it is
    * the one thing derivation cannot know about, and it is worth sending: a client should not have
    * to carry its own copy of what a card is worth to know whether it can knock.
    */
  private val arrangement: Encoder.AsObject[Arrangement] = Encoder.AsObject.derived

  given Encoder[Arrangement] = Encoder.AsObject.instance { value =>
    arrangement.encodeObject(value).add("deadwoodValue", value.deadwoodValue.asJson)
  }

  private val defence: Encoder.AsObject[Defence] = Encoder.AsObject.derived

  given Encoder[Defence] = Encoder.AsObject.instance { value =>
    defence.encodeObject(value).add("deadwoodValue", value.deadwoodValue.asJson)
  }

  given Encoder[Phase] = Encoder.instance {
    case Phase.UpcardOffered(player) =>
      tagged("phase", "upcard-offered", "player" -> player.asJson)
    case Phase.AwaitingOpeningDraw =>
      Json.obj("phase" -> Json.fromString("awaiting-opening-draw"))
    case Phase.AwaitingDraw(player) =>
      tagged("phase", "awaiting-draw", "player" -> player.asJson)
    case Phase.AwaitingDiscard(player, taken) =>
      tagged("phase", "awaiting-discard", "player" -> player.asJson, "taken" -> taken.asJson)
  }

  /** A round that nobody knocked was won by nobody, which is what the null says. */
  given Encoder[Outcome] = Encoder.AsObject.instance {
    case Outcome.Knocked(player) => JsonObject("knockedBy" -> player.asJson)
    case Outcome.Dead            => JsonObject("knockedBy" -> Json.Null)
  }

  given Encoder[RoundScore] = Encoder.instance {
    case RoundScore.Dead                     => Json.obj("result" -> Json.fromString("dead"))
    case RoundScore.Knock(winner, points)    => scored("knock", winner, points)
    case RoundScore.Gin(winner, points)      => scored("gin", winner, points)
    case RoundScore.Undercut(winner, points) => scored("undercut", winner, points)
  }

  given Encoder[RoundResult] = Encoder.AsObject.derived

  private val awaitingOpponent: Encoder.AsObject[PlayerView.AwaitingOpponent] =
    Encoder.AsObject.derived

  private val inPlay: Encoder.AsObject[PlayerView.InPlay] = Encoder.AsObject.derived

  private val over: Encoder.AsObject[PlayerView.Over] = Encoder.AsObject.derived

  given Encoder[PlayerView] = Encoder.instance {
    case view: PlayerView.AwaitingOpponent =>
      tagged("state", "awaiting-opponent", awaitingOpponent.encodeObject(view))
    case view: PlayerView.InPlay => tagged("state", "in-play", inPlay.encodeObject(view))
    case view: PlayerView.Over   => tagged("state", "over", over.encodeObject(view))
  }

  /** The name of the rule or the refusal, as a client reads it in an error body. */
  def reason(fault: GameFault): String = {
    fault match {
      case GameFault.Illegal(error) => dashed(error.productPrefix)
      case other                    => dashed(other.productPrefix)
    }
  }

  private def scored(result: String, winner: Player, points: Int): Json =
    tagged("result", result, "winner" -> winner.asJson, "points" -> points.asJson)
}
