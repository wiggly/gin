package wiggly.gin.ui

import wiggly.gin.core.domain.{Listing, PlayerView}
import wiggly.gin.core.port.Credentials

/** What the browser holds.
  *
  * Every view is kept exactly as the server sent it. The server owns the redaction, and a client
  * that re-models a view is a second author of it. The one thing the browser adds is the layout of
  * a hand, and only a round in play has a hand, so only `Playing` carries one.
  */
enum Model {

  /** `now` is the browser's clock in milliseconds, held in the model so that `View` stays a pure
    * function of it and a row's waiting time can be tested.
    */
  case Start(
      name: String,
      joining: String,
      lobby: List[Listing],
      now: Long,
      error: Option[String]
  )

  /** A seat is held but no view has arrived for it. The server publishes a game's current state to
    * a new watcher at once, so this is brief, but it is a state the model can be in.
    */
  case Connecting(credentials: Credentials)

  case Seated(
      credentials: Credentials,
      view: PlayerView.AwaitingOpponent | PlayerView.Over,
      error: Option[String]
  )

  case Playing(
      credentials: Credentials,
      view: PlayerView.InPlay,
      holding: Holding,
      error: Option[String]
  )
}
