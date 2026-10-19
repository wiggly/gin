package wiggly.gin.core.domain

import cats.{Eq, Show}

/** What a player calls themselves. A label and never a claim: two players can share one, and the
  * token stays the only thing that seats anybody.
  */
final case class PlayerName private (value: String)

object PlayerName {

  val MaxLength: Int = 20

  /** Counted in code points, so that a name outside the Basic Multilingual Plane gets the same
    * twenty as one in ASCII.
    */
  def from(raw: String): Option[PlayerName] = {
    val trimmed = raw.trim
    val length  = trimmed.codePointCount(0, trimmed.length)

    Option.when(length >= 1 && length <= MaxLength && !trimmed.exists(Character.isISOControl))(
      PlayerName(trimmed)
    )
  }

  given Eq[PlayerName] = Eq.fromUniversalEquals

  given Show[PlayerName] = Show.fromToString
}
