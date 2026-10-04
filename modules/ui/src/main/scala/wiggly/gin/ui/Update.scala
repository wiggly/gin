package wiggly.gin.ui

import cats.effect.IO
import tyrian.Cmd

/** The pure half of the application: a message and a model in, a model and a command out. */
object Update {

  def apply(model: Model, msg: Msg): (Model, Cmd[IO, Msg]) = {
    (model, msg) match {
      case (Model.Start(_, error), Msg.JoiningChanged(id)) =>
        (Model.Start(id, error), Cmd.None)

      case (Model.Start(_, _), Msg.Create) =>
        (model, Api.create)

      case (Model.Start(joining, _), Msg.Join) =>
        (model, Api.join(joining))

      case (_, Msg.Took(credentials)) =>
        (Model.Connecting(credentials), Cmd.None)

      case (Model.Connecting(credentials), Msg.Saw(view)) =>
        (Model.Seated(credentials, view, None), Cmd.None)

      case (Model.Seated(credentials, _, _), Msg.Saw(view)) =>
        (Model.Seated(credentials, view, None), Cmd.None)

      case (Model.Seated(credentials, view, _), Msg.Play(move)) =>
        (Model.Seated(credentials, view, None), Api.play(credentials, move))

      case (Model.Start(joining, _), Msg.Failed(reason)) =>
        (Model.Start(joining, Some(reason)), Cmd.None)

      case (Model.Seated(credentials, view, _), Msg.Failed(reason)) =>
        (Model.Seated(credentials, view, Some(reason)), Cmd.None)

      // A message that does not belong to the state the model is in. The only way to reach one is
      // a reply arriving after the model moved on, so dropping it is right.
      case _ => (model, Cmd.None)
    }
  }
}
