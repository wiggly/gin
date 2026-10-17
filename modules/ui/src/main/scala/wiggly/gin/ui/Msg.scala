package wiggly.gin.ui

import wiggly.gin.core.domain.{Card, Move, PlayerView}
import wiggly.gin.core.port.Credentials

/** What the person did, and what came back.
  *
  * A failure is a message like any other, because the model has somewhere to put a reason and
  * nowhere to put a throwable.
  */
enum Msg {
  case JoiningChanged(id: String)
  case Create
  case Join
  case Play(move: Move)
  case Took(credentials: Credentials)
  case Saw(view: PlayerView)
  case Failed(reason: String)
  case Toggle(card: Card)
  case Sort(by: Sorting)
  case Lift(card: Card)
  case Drop(before: Option[Card])

  /** A drop target has to cancel `dragover`, and a Tyrian handler has to return a message to do
    * it. This is that message, and nothing handles it.
    */
  case Hover
}
