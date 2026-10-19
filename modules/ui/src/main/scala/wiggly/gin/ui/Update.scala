package wiggly.gin.ui

import cats.effect.IO
import tyrian.Cmd
import wiggly.gin.core.domain.{PlayerName, PlayerView}
import wiggly.gin.core.port.Credentials

/** The pure half of the application: a message and a model in, a model and a command out. */
object Update {

  val NameWanted: String = s"Type a name of 1 to ${PlayerName.MaxLength} characters."

  def apply(model: Model, msg: Msg): (Model, Cmd[IO, Msg]) = {
    (model, msg) match {
      case (start: Model.Start, Msg.NameChanged(name)) =>
        (start.copy(name = name), Cmd.None)

      case (start: Model.Start, Msg.JoiningChanged(id)) =>
        (start.copy(joining = id), Cmd.None)

      case (start: Model.Start, Msg.Create) =>
        named(start)(Api.create)

      case (start: Model.Start, Msg.Join) =>
        named(start)(Api.join(start.joining, _))

      case (_, Msg.Took(credentials)) =>
        (Model.Connecting(credentials), Cmd.None)

      case (Model.Connecting(credentials), Msg.Saw(view)) =>
        (seat(credentials, view, None), Cmd.None)

      case (Model.Seated(credentials, _, _), Msg.Saw(view)) =>
        (seat(credentials, view, None), Cmd.None)

      case (Model.Playing(credentials, current, holding, _), Msg.Saw(view)) =>
        (seat(credentials, view, Some((current, holding))), Cmd.None)

      case (Model.Playing(credentials, view, holding, _), Msg.Play(move)) =>
        (Model.Playing(credentials, view, holding, None), Api.play(credentials, move))

      case (playing: Model.Playing, Msg.Toggle(card)) =>
        (playing.copy(holding = playing.holding.toggled(card, playing.view)), Cmd.None)

      case (playing: Model.Playing, Msg.Sort(by)) =>
        (playing.copy(holding = playing.holding.sorted(by, playing.view)), Cmd.None)

      case (playing: Model.Playing, Msg.Lift(card)) =>
        (playing.copy(holding = playing.holding.lifted(card)), Cmd.None)

      case (playing: Model.Playing, Msg.Enter(slot)) =>
        (playing.copy(holding = playing.holding.hovered(slot)), Cmd.None)

      case (playing: Model.Playing, Msg.Drop) =>
        (playing.copy(holding = playing.holding.dropped), Cmd.None)

      case (playing: Model.Playing, Msg.Release) =>
        (playing.copy(holding = playing.holding.released), Cmd.None)

      case (start: Model.Start, Msg.Failed(reason)) =>
        (start.copy(error = Some(reason)), Cmd.None)

      case (seated: Model.Seated, Msg.Failed(reason)) =>
        (seated.copy(error = Some(reason)), Cmd.None)

      case (playing: Model.Playing, Msg.Failed(reason)) =>
        (playing.copy(error = Some(reason)), Cmd.None)

      // A message that does not belong to the state the model is in. A reply arriving after the
      // model moved on is one way to reach this, and `Hover` is another, so dropping it is right.
      case _ => (model, Cmd.None)
    }
  }

  /** A seat is asked for only under a name the server accepts, so that a bad one is answered at
    * once rather than after a round trip.
    */
  private def named(start: Model.Start)(ask: PlayerName => Cmd[IO, Msg]): (Model, Cmd[IO, Msg]) = {
    PlayerName.from(start.name) match {
      case Some(name) => (start.copy(error = None), ask(name))
      case None       => (start.copy(error = Some(NameWanted)), Cmd.None)
    }
  }

  /** A hand carries over from one view to the next while a round is in play, which is what keeps
    * the order a player chose. A new deal starts from the server's arrangement instead.
    */
  private def seat(
      credentials: Credentials,
      view: PlayerView,
      kept: Option[(PlayerView.InPlay, Holding)]
  ): Model = {
    view match {
      case playing: PlayerView.InPlay => {
        // Within a round the stock only shrinks, and a new deal restores it, so a larger stock is
        // how a new deal is recognised. A new hand shares cards with the old one by chance only.
        val holding = kept match {
          case Some((before, held)) if playing.stockSize <= before.stockSize =>
            held.reconcile(playing)
          case _ => Holding.of(playing)
        }

        Model.Playing(credentials, playing, holding, None)
      }
      case waiting: PlayerView.AwaitingOpponent => Model.Seated(credentials, waiting, None)
      case over: PlayerView.Over                => Model.Seated(credentials, over, None)
    }
  }
}
