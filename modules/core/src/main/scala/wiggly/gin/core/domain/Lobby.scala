package wiggly.gin.core.domain

import cats.{Eq, Show}

/** Epoch milliseconds rather than an `Instant`, because `core` is cross-built and an `Instant` on
  * Scala.js needs a library the build does not carry.
  */
final case class ListedAt(millis: Long)

object ListedAt {
  given Eq[ListedAt] = Eq.fromUniversalEquals

  given Show[ListedAt] = Show.fromToString
}

/** One row of the lobby. `since` is when the host's stream opened, so the waiting time a player
  * reads is how long the host has been there.
  */
final case class Listing(id: GameId, host: PlayerName, since: ListedAt)

object Listing {
  given Eq[Listing] = Eq.fromUniversalEquals

  given Show[Listing] = Show.fromToString
}

/** The games waiting for an opponent whose hosts are watching them.
  *
  * A row counts the streams that hold it, because a host with two tabs, or a refresh whose new
  * stream opens before the old one closes, is still there when one stream goes. Only the
  * transitions below build a lobby, and none of them leaves a row on a count below one.
  */
final case class Lobby private (rows: Map[GameId, Lobby.Row]) {

  def opened(id: GameId, host: PlayerName, at: ListedAt): Lobby = {
    val row = rows.get(id) match {
      case Some(held) => held.copy(watchers = held.watchers + 1)
      case None       => Lobby.Row(Listing(id, host, at), 1)
    }

    Lobby(rows.updated(id, row))
  }

  def closed(id: GameId): Lobby = {
    rows.get(id) match {
      case Some(held) if held.watchers > 1 =>
        Lobby(rows.updated(id, held.copy(watchers = held.watchers - 1)))
      case _ => Lobby(rows.removed(id))
    }
  }

  def taken(id: GameId): Lobby = Lobby(rows.removed(id))

  /** The id breaks a tie, so that two games listed in the same millisecond keep one order. */
  def listings: List[Listing] =
    rows.values.map(_.listing).toList.sortBy(listing => (listing.since.millis, listing.id.value))
}

object Lobby {

  final case class Row(listing: Listing, watchers: Int)

  val empty: Lobby = Lobby(Map.empty)

  given Eq[Lobby] = Eq.fromUniversalEquals

  given Show[Lobby] = Show.fromToString
}
