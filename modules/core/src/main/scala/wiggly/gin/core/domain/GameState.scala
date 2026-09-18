package wiggly.gin.core.domain

import cats.syntax.all.*
import cats.{Eq, Show}

/** Where every card in the round is: the stock to draw from, the discard pile with its top card
  * first, and what each player holds.
  *
  * These four places are the whole round. A move takes a card from one and puts it in another and
  * never makes one, so a table dealt from a [[Deck]] stays that deck for as long as the round lasts.
  */
final case class Table(stock: List[Card], discard: List[Card], hands: Hands)

object Table {
  given Eq[Table]   = Eq.fromUniversalEquals
  given Show[Table] = Show.fromToString
}

/** What the round is waiting for.
  *
  * A player holds ten cards in every phase but [[Phase.AwaitingDiscard]], where they hold eleven,
  * so the count is a consequence of the phase rather than something to check.
  */
enum Phase {

  /** The upcard is offered to this player, who can take it or pass. */
  case UpcardOffered(player: Player)

  /** Both players passed, so the non-dealer draws from the stock and only from the stock. */
  case AwaitingOpeningDraw

  case AwaitingDraw(player: Player)

  /** The player drew and owes a discard. `taken` is the card they lifted from the pile this turn,
    * which they cannot put straight back.
    */
  case AwaitingDiscard(player: Player, taken: Option[Card])

  /** Whose move it is. [[AwaitingOpeningDraw]] is the one phase that names nobody, because it is
    * always the non-dealer's, both players having just refused the upcard. That is the only rule
    * in the round that reads the seating, and it is why this needs one.
    */
  def onTurn(seats: Seats): Player = {
    this match {
      case UpcardOffered(player)      => player
      case AwaitingOpeningDraw        => seats.nonDealer
      case AwaitingDraw(player)       => player
      case AwaitingDiscard(player, _) => player
    }
  }
}

object Phase {
  given Eq[Phase] = Eq.fromUniversalEquals

  given Show[Phase] = Show.fromToString
}

/** How a round ended.
  *
  * Gin is not here. A knocker who left nothing behind has gin, and the finished table holds the
  * hand that proves it, so scoring reads the arrangement rather than a second flag that could
  * disagree with it.
  */
enum Outcome {
  case Knocked(player: Player)

  /** The stock ran down and nobody scores. */
  case Dead
}

object Outcome {
  given Eq[Outcome] = Eq.fromUniversalEquals

  given Show[Outcome] = Show.fromToString
}

/** A round of gin rummy, either still being played or over.
  *
  * The split is where the machine splits. A finished round has no player on turn and no phase, and
  * saying that in the type turns a guard repeated in every branch into one refusal.
  */
sealed trait GameState {
  def table: Table

  /** Who dealt this round. A round that cannot say so is a round that has to be carried everywhere
    * beside something that can.
    */
  def seats: Seats
}

object GameState {

  final case class InProgress(table: Table, seats: Seats, phase: Phase) extends GameState {
    def onTurn: Player = phase.onTurn(seats)
  }

  final case class Finished(table: Table, seats: Seats, outcome: Outcome) extends GameState

  /** The cards a player holds between turns. */
  val HandSize: Int = 10

  /** The most deadwood a knock can leave behind. */
  val KnockThreshold: Int = 10

  /** The last two cards of the stock are never drawn. A discard that leaves the stock holding this
    * many ends the round with no score.
    */
  val StockFloor: Int = 2

  /** Ten cards each, the next card face up, and the rest as the stock.
    *
    * The deck arrives already shuffled, because a shuffle is an effect and the domain has none. The
    * non-dealer is dealt first and is offered the upcard first, which is the only asymmetry between
    * the two seats.
    */
  def deal(deck: Deck, seats: Seats): InProgress = {
    val (first, afterFirst)   = deck.cards.splitAt(HandSize)
    val (second, afterSecond) = afterFirst.splitAt(HandSize)
    val (upcard, stock)       = afterSecond.splitAt(1)

    val hands = seats.nonDealer match {
      case Player.One => Hands(Hand.of(first), Hand.of(second))
      case Player.Two => Hands(Hand.of(second), Hand.of(first))
    }

    InProgress(Table(stock, upcard, hands), seats, Phase.UpcardOffered(seats.nonDealer))
  }

  /** This move played by this player, or why it was refused. */
  def apply(state: GameState, player: Player, move: Move): Either[GameError, GameState] = {
    state match {
      case _: Finished                             => Left(GameError.RoundOver)
      case state @ InProgress(table, seats, phase) =>
        if (player =!= state.onTurn) Left(GameError.NotYourTurn)
        else step(table, seats, phase, move)
    }
  }

  private def step(
      table: Table,
      seats: Seats,
      phase: Phase,
      move: Move
  ): Either[GameError, GameState] = {
    (phase, move) match {
      case (Phase.UpcardOffered(player), Move.DrawDiscard) => takeUpcard(table, seats, player)
      case (Phase.UpcardOffered(player), Move.Pass) if player === seats.nonDealer =>
        Right(InProgress(table, seats, Phase.UpcardOffered(seats.dealer)))
      case (Phase.UpcardOffered(_), Move.Pass) =>
        Right(InProgress(table, seats, Phase.AwaitingOpeningDraw))
      case (Phase.UpcardOffered(_), Move.DrawStock) => Left(GameError.StockClosed)
      case (Phase.UpcardOffered(_), _)              => Left(GameError.MustDraw)

      case (Phase.AwaitingOpeningDraw, Move.DrawStock)   => drawStock(table, seats, seats.nonDealer)
      case (Phase.AwaitingOpeningDraw, Move.DrawDiscard) => Left(GameError.PileClosed)
      case (Phase.AwaitingOpeningDraw, Move.Pass)        => Left(GameError.NothingToPass)
      case (Phase.AwaitingOpeningDraw, _)                => Left(GameError.MustDraw)

      case (Phase.AwaitingDraw(player), Move.DrawStock)   => drawStock(table, seats, player)
      case (Phase.AwaitingDraw(player), Move.DrawDiscard) => takeUpcard(table, seats, player)
      case (Phase.AwaitingDraw(_), Move.Pass)             => Left(GameError.NothingToPass)
      case (Phase.AwaitingDraw(_), _)                     => Left(GameError.MustDraw)

      case (Phase.AwaitingDiscard(player, taken), Move.Discard(card)) =>
        discard(table, seats, player, taken, card, ending = false)
      case (Phase.AwaitingDiscard(player, taken), Move.Knock(card)) =>
        discard(table, seats, player, taken, card, ending = true)
      case (Phase.AwaitingDiscard(_, _), Move.Pass) => Left(GameError.NothingToPass)
      case (Phase.AwaitingDiscard(_, _), _)         => Left(GameError.MustDiscard)
    }
  }

  /** The pile is empty only between a take and the discard that follows it, and no draw is legal
    * there, so the refusal below is unreachable rather than a rule of its own.
    */
  private def takeUpcard(
      table: Table,
      seats: Seats,
      player: Player
  ): Either[GameError, GameState] = {
    table.discard match {
      case Nil          => Left(GameError.PileClosed)
      case card :: rest =>
        Right(
          InProgress(
            held(table.copy(discard = rest), player, card),
            seats,
            Phase.AwaitingDiscard(player, Some(card))
          )
        )
    }
  }

  /** A round dies on the discard that leaves [[StockFloor]] cards, so a draw never finds the stock
    * empty and the refusal below is unreachable in the same way.
    */
  private def drawStock(
      table: Table,
      seats: Seats,
      player: Player
  ): Either[GameError, GameState] = {
    table.stock match {
      case Nil          => Left(GameError.StockClosed)
      case card :: rest =>
        Right(
          InProgress(
            held(table.copy(stock = rest), player, card),
            seats,
            Phase.AwaitingDiscard(player, None)
          )
        )
    }
  }

  private def held(table: Table, player: Player, card: Card): Table =
    table.copy(hands = table.hands.updated(player, table.hands(player).add(card)))

  private def discard(
      table: Table,
      seats: Seats,
      player: Player,
      taken: Option[Card],
      card: Card,
      ending: Boolean
  ): Either[GameError, GameState] = {
    if (taken.exists(_ === card)) Left(GameError.CannotDiscardDrawnCard(card))
    else {
      table.hands(player).remove(card) match {
        case None       => Left(GameError.CardNotHeld(card))
        case Some(hand) => {
          val settled =
            table.copy(discard = card :: table.discard, hands = table.hands.updated(player, hand))

          if (ending) knock(settled, seats, player, hand)
          else if (settled.stock.size <= StockFloor) Right(Finished(settled, seats, Outcome.Dead))
          else Right(InProgress(settled, seats, Phase.AwaitingDraw(player.other)))
        }
      }
    }
  }

  private def knock(
      table: Table,
      seats: Seats,
      player: Player,
      hand: Hand
  ): Either[GameError, GameState] = {
    val deadwood = hand.deadwoodValue

    if (deadwood > KnockThreshold) Left(GameError.CannotKnock(deadwood))
    else Right(Finished(table, seats, Outcome.Knocked(player)))
  }

  given Eq[GameState] = Eq.fromUniversalEquals

  given Show[GameState] = Show.fromToString
}
