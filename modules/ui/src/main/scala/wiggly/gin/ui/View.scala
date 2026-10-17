package wiggly.gin.ui

import cats.syntax.all.*
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
      case Model.Start(joining, error)      => start(joining, error)
      case Model.Connecting(_)              => div(cls := "waiting")(text("Taking your seat..."))
      case Model.Seated(_, view, error)     => seated(view, error)
      case Model.Playing(_, view, _, error) => table(view, error)
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

  private def table(view: PlayerView.InPlay, error: Option[String]): Html[Msg] = {
    div(cls := "table")(
      scoreboard(view),
      opponent(view),
      pile(view),
      hand(view),
      moves(view),
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

  private def pile(view: PlayerView.InPlay): Html[Msg] = {
    div(cls := "piles")(
      div(cls := "stock")(Cards.back, p(text(s"Stock ${view.stockSize}"))),
      div(cls := "upcard")(
        view.upcard.fold(p(cls := "empty-pile")(text("The pile is empty")))(Cards.face),
        p(text(s"Pile ${view.discardSize}"))
      )
    )
  }

  /** A hand reads as its arrangement: each meld on its own row, the deadwood last. That is the
    * grouping the player needs, because it is the one their score is counted from.
    */
  private def hand(view: PlayerView.InPlay): Html[Msg] = {
    div(cls := "hand")(
      div(cls := "melds")(
        view.arrangement.melds.map(meld =>
          div(cls := "fan")(meld.cards.toList.map(Cards.face[Msg]))
        )
      ),
      div(cls := "fan")(view.arrangement.deadwood.map(Cards.face[Msg])),
      p(cls := "count")(text(s"Deadwood ${view.arrangement.deadwoodValue}"))
    )
  }

  /** Every button is a move the rules would accept, because both sides read the same function. */
  private def moves(view: PlayerView.InPlay): Html[Msg] =
    div(cls := "moves")(
      Moves.available(view).map(move => button(onClick(Msg.Play(move)))(text(label(move))))
    )

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
