package wiggly.gin.protocol

import cats.{Eq, Show}
import wiggly.gin.core.domain.PlayerName

/** The body of a request for a seat: who is sitting down. */
final case class Introduction(name: PlayerName)

object Introduction {
  given Eq[Introduction] = Eq.fromUniversalEquals

  given Show[Introduction] = Show.fromToString
}
