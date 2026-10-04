package wiggly.gin.ui

import wiggly.gin.core.domain.{Move, PlayerView}
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
}
