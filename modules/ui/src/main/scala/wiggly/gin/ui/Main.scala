package wiggly.gin.ui

import cats.effect.IO
import org.scalajs.dom
import tyrian.*
import wiggly.gin.core.domain.GameId

import scala.scalajs.js.annotation.JSExportTopLevel

/** The browser application.
  *
  * The composition root, and the only place in this module that reads the page it is running in.
  */
@JSExportTopLevel("TyrianApp")
object Main extends TyrianIOApp[Msg, Model] {

  def router: Location => Msg = Routing.none(Msg.Failed("no such page"))

  /** A link to a game opens that game. A seat already held for it is taken up again, because a
    * reload must not cost a player the game they are in the middle of.
    */
  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) = {
    val arriving = Seat.gameIdIn(dom.window.location.pathname)

    arriving.flatMap(id => Seat.recall(GameId(id))) match {
      case Some(seat) => (Model.Connecting(seat), Cmd.None)
      case None       =>
        (Model.Start(Profile.recall.getOrElse(""), arriving.getOrElse(""), None), Cmd.None)
    }
  }

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) = { msg =>
    // Writing the seat down belongs where the seat first becomes known. A command could be
    // dropped by a model that moved on, and then a reload would lose the game.
    msg match {
      case Msg.Took(seat)        => Seat.remember(seat)
      case Msg.NameChanged(name) => Profile.remember(name)
      case _                     => ()
    }

    Update(model, msg)
  }

  def view(model: Model): Html[Msg] = View(model)

  def subscriptions(model: Model): Sub[IO, Msg] = {
    model match {
      case Model.Connecting(seat)       => Events.watch(seat)
      case Model.Seated(seat, _, _)     => Events.watch(seat)
      case Model.Playing(seat, _, _, _) => Events.watch(seat)
      case Model.Start(_, _, _)         => Sub.None
    }
  }
}
