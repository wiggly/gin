package wiggly.gin.ui

import cats.syntax.all.*
import org.scalajs.dom
import tyrian.Aria
import tyrian.Attr
import tyrian.Html
import tyrian.Html.*
import wiggly.gin.core.domain.*

/** The screens.
  *
  * Once a player holds a seat the screens are the cases of [[PlayerView]] and no others, so there
  * is no state here that the server does not have. A token is never drawn: it is the player's
  * claim to their seat, and a page is a thing people point cameras at.
  */
object View {

  def apply(model: Model): Html[Msg] = {
    model match {
      case Model.Start(joining, error)  => start(joining, error)
      case Model.Connecting(_)          => div(cls := "waiting")(text("Taking your seat..."))
      case Model.Seated(_, view, error) => seated(view, error)
      case Model.Playing(_, view, holding, error) => table(view, holding, error)
    }
  }

  private def start(joining: String, error: Option[String]): Html[Msg] = {
    div(cls := "start")(
      h1(text("Gin Rummy")),
      button(onClick(Msg.Create))(text("Create a game")),
      p(text("or join one you were sent:")),
      input(
        value := joining,
        onInput(id => Msg.JoiningChanged(id)),
        placeholder := "game id"
      ),
      button(onClick(Msg.Join))(text("Join")),
      problem(error)
    )
  }

  private def seated(
      view: PlayerView.AwaitingOpponent | PlayerView.Over,
      error: Option[String]
  ): Html[Msg] = {
    view match {
      case waiting: PlayerView.AwaitingOpponent => awaiting(waiting, error)
      case over: PlayerView.Over                => finished(over)
    }
  }

  private def awaiting(view: PlayerView.AwaitingOpponent, error: Option[String]): Html[Msg] = {
    div(cls := "waiting")(
      h1(text("Waiting for the other player")),
      p(text("Send them this link:")),
      code(cls := "game-id")(text(Seat.linkTo(view.id))),
      p(cls := "or")(text(s"or the game id on its own: ${view.id.value}")),
      problem(error)
    )
  }

  private def table(view: PlayerView.InPlay, holding: Holding, error: Option[String]): Html[Msg] = {
    val available = Moves.available(view)

    div(cls := "table")(
      scoreboard(view),
      opponent(view),
      piles(view, available),
      hand(view, holding, available),
      moves(holding, available),
      view.previous.fold(empty)(lastRound),
      problem(error)
    )
  }

  private def scoreboard(view: PlayerView.InPlay): Html[Msg] = {
    val turn = if (view.onTurn === view.you) "Your turn" else "Waiting for the other player"

    div(cls := "scoreboard")(
      p(text(s"You ${view.totals(view.you)}")),
      p(text(s"Them ${view.totals(view.you.other)}")),
      p(cls := "turn")(text(turn))
    )
  }

  private def opponent(view: PlayerView.InPlay): Html[Msg] =
    div(cls := "opponent fan")(List.fill(view.opponentSize)(Cards.back[Msg]))

  private def piles(view: PlayerView.InPlay, available: List[Move]): Html[Msg] = {
    val upcard = view.upcard.fold(p(cls := "empty-pile")(text("The pile is empty"))) { up =>
      draw(Move.DrawDiscard, available)(Cards.face(up))
    }

    div(cls := "piles")(
      div(cls := "stock")(
        draw(Move.DrawStock, available)(Cards.back),
        p(text(s"Stock ${view.stockSize}"))
      ),
      div(cls := "upcard")(upcard, p(text(s"Pile ${view.discardSize}")))
    )
  }

  /** The order is the player's. Which meld a card is in is the server's, so it is drawn as a tint
    * that travels with the card rather than as a position the player could break up.
    */
  private def hand(view: PlayerView.InPlay, holding: Holding, available: List[Move]): Html[Msg] = {
    val tints = view.arrangement.melds.zipWithIndex.flatMap { case (meld, i) =>
      meld.cards.toList.map(_ -> s"meld-$i")
    }.toMap

    div(cls := "hand")(
      div(cls := "fan")(
        holding.shown.zipWithIndex.map { case (card, at) =>
          slot(
            card,
            at,
            tints.get(card),
            available.contains(Move.Discard(card)),
            holding.selected.contains(card),
            holding.dragging.contains(card)
          )
        } :+ div(
          cls := "end",
          onDragEnter(Msg.Enter(holding.order.size)),
          onDragOver(Msg.Hover),
          onDrop(Msg.Drop)
        )()
      ),
      div(cls := "sorting")(
        Sorting.values.toList.map(by => button(onClick(Msg.Sort(by)))(text(sortLabel(by))))
      ),
      p(cls := "count")(text(s"Deadwood ${view.arrangement.deadwoodValue}"))
    )
  }

  private def slot(
      card: Card,
      at: Int,
      tint: Option[String],
      selectable: Boolean,
      selected: Boolean,
      lifted: Boolean
  ): Html[Msg] = {
    val classes = "slot" :: tint.toList ++
      Option.when(selected)("selected").toList ++
      Option.when(lifted)("lifted").toList

    div(
      cls := classes.mkString(" "),
      attribute("draggable", "true"),
      lift(card),
      onDragEnter(Msg.Enter(at)),
      onDragOver(Msg.Hover),
      onDrop(Msg.Drop),
      onDragEnd(Msg.Release)
    )(pick(selectable, Msg.Toggle(card), Aria.ariaPressed := selected.toString)(Cards.face(card)))
  }

  /** Tyrian cancels the default of every event unless told not to, and cancelling `dragstart`
    * cancels the drag. Firefox also starts a drag only once something is on the transfer.
    */
  private def lift(card: Card): Attr[Msg] = {
    tyrian
      .Event(
        "dragstart",
        (event: dom.DragEvent) => {
          event.dataTransfer.setData("text/plain", Cards.name(card))
          Msg.Lift(card)
        }
      )
      .noPreventDefault
  }

  /** A discard names the card it acts on, so in a discard phase the buttons wait for one. */
  private def moves(holding: Holding, available: List[Move]): Html[Msg] = {
    val discarding = available.exists {
      case Move.Discard(_) => true
      case _               => false
    }

    val buttons =
      if (discarding) {
        holding.selected.fold(List(button(disabled(true))(text("Select a card to discard")))) {
          card => List(Move.Discard(card), Move.Knock(card)).filter(available.contains).map(play)
        }
      } else {
        available.filter(_ === Move.Pass).map(play)
      }

    div(cls := "moves")(buttons)
  }

  private def play(move: Move): Html[Msg] = button(onClick(Msg.Play(move)))(text(label(move)))

  /** Something a player can click is a button, so that the keyboard reaches it as well. */
  private def pick(enabled: Boolean, msg: Msg, describing: Attr[Msg])(art: Html[Msg]): Html[Msg] =
    if (enabled) button(cls := "pick", onClick(msg), describing)(art) else art

  /** A pile's picture names the card, and the button has to name what pressing it does. */
  private def draw(move: Move, available: List[Move])(art: Html[Msg]): Html[Msg] =
    pick(available.contains(move), Msg.Play(move), Aria.ariaLabel := label(move))(art)

  private def sortLabel(by: Sorting): String = {
    by match {
      case Sorting.ByMelds => "By melds"
      case Sorting.BySuit  => "By suit"
      case Sorting.ByRank  => "By rank"
    }
  }

  private def lastRound(result: RoundResult): Html[Msg] = {
    val said = result.score match {
      case RoundScore.Dead                     => "The stock ran down. Nobody scored."
      case RoundScore.Knock(winner, points)    => s"${who(winner)} knocked for $points."
      case RoundScore.Gin(winner, points)      => s"${who(winner)} went gin for $points."
      case RoundScore.Undercut(winner, points) => s"${who(winner)} undercut for $points."
    }

    div(cls := "previous")(h2(text("The round before")), p(text(said)))
  }

  private def finished(view: PlayerView.Over): Html[Msg] = {
    val said =
      if (view.result.winner === view.you) "You won the match" else "The other player won the match"

    div(cls := "over")(
      h1(text(said)),
      p(text(s"You ${view.result.totals(view.you)}, them ${view.result.totals(view.you.other)}"))
    )
  }

  private def label(move: Move): String = {
    move match {
      case Move.DrawStock   => "Draw from the stock"
      case Move.DrawDiscard => "Take the upcard"
      case Move.Pass        => "Pass"
      case Move.Discard(c)  => s"Discard ${Cards.short(c)}"
      case Move.Knock(c)    => s"Knock with ${Cards.short(c)}"
    }
  }

  private def who(player: Player): String = s"Player ${player.toString.toLowerCase}"

  private def problem(error: Option[String]): Html[Msg] =
    error.fold(empty)(reason => div(cls := "error")(text(reason)))

  private val empty: Html[Msg] = span()()
}
