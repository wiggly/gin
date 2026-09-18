package wiggly.gin.server.adapter.http

import io.circe.syntax.*
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import wiggly.gin.core.domain.*
import wiggly.gin.core.port.Credentials

/** How the game looks on the wire.
  *
  * Written out rather than derived, so that the names a client sees are chosen and do not move
  * when a field is renamed in Scala. Everything here is one way except a move, because a move is
  * the only thing a client sends.
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

  given Encoder[Rank] = named
  given Decoder[Rank] = parsed(Rank.values, "rank")

  given Encoder[Suit] = named
  given Decoder[Suit] = parsed(Suit.values, "suit")

  given Encoder[Player] = named

  given Encoder[Card] = Encoder.instance { card =>
    Json.obj("rank" -> card.rank.asJson, "suit" -> card.suit.asJson)
  }

  given Decoder[Card] = Decoder.instance { cursor =>
    for {
      rank <- cursor.get[Rank]("rank")
      suit <- cursor.get[Suit]("suit")
    } yield Card(rank, suit)
  }

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

    Json.obj("kind" -> Json.fromString(kind), "cards" -> meld.cards.toList.asJson)
  }

  given Encoder[Arrangement] = Encoder.instance { arrangement =>
    Json.obj(
      "melds"         -> arrangement.melds.asJson,
      "deadwood"      -> arrangement.deadwood.asJson,
      "deadwoodValue" -> arrangement.deadwoodValue.asJson
    )
  }

  given Encoder[Defence] = Encoder.instance { defence =>
    Json.obj(
      "melds"         -> defence.melds.asJson,
      "layoffs"       -> defence.layoffs.asJson,
      "deadwood"      -> defence.deadwood.asJson,
      "deadwoodValue" -> defence.deadwoodValue.asJson
    )
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

  given Encoder[Tally] = Encoder.instance { tally =>
    Json.obj("one" -> tally.one.asJson, "two" -> tally.two.asJson)
  }

  given Encoder[RoundScore] = Encoder.instance {
    case RoundScore.Dead                     => Json.obj("result" -> Json.fromString("dead"))
    case RoundScore.Knock(winner, points)    => scored("knock", winner, points)
    case RoundScore.Gin(winner, points)      => scored("gin", winner, points)
    case RoundScore.Undercut(winner, points) => scored("undercut", winner, points)
  }

  given Encoder[RoundResult] = Encoder.instance { result =>
    val knockedBy = result.outcome match {
      case Outcome.Knocked(player) => player.asJson
      case Outcome.Dead            => Json.Null
    }

    Json.obj(
      "knockedBy" -> knockedBy,
      "knocker"   -> result.knocker.asJson,
      "defender"  -> result.defender.asJson,
      "score"     -> result.score.asJson
    )
  }

  given Encoder[MatchResult] = Encoder.instance { result =>
    Json.obj("winner" -> result.winner.asJson, "totals" -> result.totals.asJson)
  }

  given Encoder[PlayerView] = Encoder.instance {
    case PlayerView.AwaitingOpponent(id, you) =>
      tagged("state", "awaiting-opponent", "id" -> id.value.asJson, "you" -> you.asJson)

    case view: PlayerView.InPlay =>
      tagged(
        "state",
        "in-play",
        "id"           -> view.id.value.asJson,
        "you"          -> view.you.asJson,
        "arrangement"  -> view.arrangement.asJson,
        "dealer"       -> view.dealer.asJson,
        "onTurn"       -> view.onTurn.asJson,
        "phase"        -> view.phase.asJson,
        "upcard"       -> view.upcard.asJson,
        "stockSize"    -> view.stockSize.asJson,
        "discardSize"  -> view.discardSize.asJson,
        "opponentSize" -> view.opponentSize.asJson,
        "totals"       -> view.totals.asJson,
        "previous"     -> view.previous.asJson
      )

    case PlayerView.Over(id, you, result, previous) =>
      tagged(
        "state",
        "over",
        "id"       -> id.value.asJson,
        "you"      -> you.asJson,
        "result"   -> result.asJson,
        "previous" -> previous.asJson
      )
  }

  given Encoder[Credentials] = Encoder.instance { credentials =>
    Json.obj(
      "id"    -> credentials.id.value.asJson,
      "you"   -> credentials.you.asJson,
      "token" -> credentials.token.value.asJson
    )
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

  private def tagged(key: String, value: String, fields: (String, Json)*): Json =
    Json.obj(((key -> Json.fromString(value)) +: fields)*)
}
