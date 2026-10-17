package wiggly.gin.ui

import cats.effect.IO
import tyrian.Cmd
import wiggly.gin.core.domain.PlayerView
import wiggly.gin.core.port.Credentials

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
        (seat(credentials, view, None), Cmd.None)

      case (Model.Seated(credentials, _, _), Msg.Saw(view)) =>
        (seat(credentials, view, None), Cmd.None)

      case (Model.Playing(credentials, _, holding, _), Msg.Saw(view)) =>
        (seat(credentials, view, Some(holding)), Cmd.None)

      case (Model.Playing(credentials, view, holding, _), Msg.Play(move)) =>
        (Model.Playing(credentials, view, holding, None), Api.play(credentials, move))

      case (playing: Model.Playing, Msg.Toggle(card)) =>
        (playing.copy(holding = playing.holding.toggled(card, playing.view)), Cmd.None)

      case (playing: Model.Playing, Msg.Sort(by)) =>
        (playing.copy(holding = playing.holding.sorted(by, playing.view)), Cmd.None)

      case (playing: Model.Playing, Msg.Lift(card)) =>
        (playing.copy(holding = playing.holding.lifted(card)), Cmd.None)

      case (playing: Model.Playing, Msg.Drop(before)) =>
        (playing.copy(holding = playing.holding.moved(before)), Cmd.None)

      case (Model.Start(joining, _), Msg.Failed(reason)) =>
        (Model.Start(joining, Some(reason)), Cmd.None)

      case (seated: Model.Seated, Msg.Failed(reason)) =>
        (seated.copy(error = Some(reason)), Cmd.None)

      case (playing: Model.Playing, Msg.Failed(reason)) =>
        (playing.copy(error = Some(reason)), Cmd.None)

      // A message that does not belong to the state the model is in. A reply arriving after the
      // model moved on is one way to reach this, and `Hover` is another, so dropping it is right.
      case _ => (model, Cmd.None)
    }
  }

  /** A hand carries over from one view to the next while a round is in play, which is what keeps
    * the order a player chose. A new round deals new cards, and reconciling then keeps none of
    * them, so it needs no case of its own.
    */
  private def seat(credentials: Credentials, view: PlayerView, kept: Option[Holding]): Model = {
    view match {
      case playing: PlayerView.InPlay =>
        Model.Playing(
          credentials,
          playing,
          kept.fold(Holding.of(playing))(_.reconcile(playing)),
          None
        )
      case waiting: PlayerView.AwaitingOpponent => Model.Seated(credentials, waiting, None)
      case over: PlayerView.Over                => Model.Seated(credentials, over, None)
    }
  }
}
