package wiggly.gin.ui

import wiggly.gin.core.domain.PlayerView
import wiggly.gin.core.port.Credentials

/** What the browser holds.
  *
  * `Seated` keeps the view exactly as the server sent it. The server owns the redaction, and a
  * client that re-models a view is a second author of it.
  */
enum Model {

  case Start(joining: String, error: Option[String])

  /** A seat is held but no view has arrived for it. The server publishes a game's current state to
    * a new watcher at once, so this is brief, but it is a state the model can be in.
    */
  case Connecting(credentials: Credentials)

  case Seated(credentials: Credentials, view: PlayerView, error: Option[String])
}
