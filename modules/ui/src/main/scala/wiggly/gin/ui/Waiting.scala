package wiggly.gin.ui

import wiggly.gin.core.domain.ListedAt

/** How long a game in the lobby has waited, in the words a row shows. */
object Waiting {

  def age(since: ListedAt, now: Long): String = {
    val minutes = (now - since.millis).max(0L) / 60000L

    if (minutes == 0L) "waiting under a minute" else s"waiting $minutes min"
  }
}
