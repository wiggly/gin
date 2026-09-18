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
  given Eq[Table] = Eq.fromUniversalEquals

  given Show[Table] = Show.show { table =>
    show"Table(stock = ${table.stock.size}, discard = ${table.discard.size}, ${table.hands})"
  }
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

  /** Whose move it is. [[AwaitingOpeningDraw]] names nobody because it is always the non-dealer's,
    * both players having just refused the upcard.
    */
  def onTurn: Player = {
    this match {
      case UpcardOffered(player)      => player
      case AwaitingOpeningDraw        => Player.NonDealer
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
}

object GameState {

  final case class InProgress(table: Table, phase: Phase) extends GameState

  final case class Finished(table: Table, outcome: Outcome) extends GameState

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
  def deal(deck: Deck): InProgress = {
    val (nonDealer, afterNonDealer) = deck.cards.splitAt(HandSize)
    val (dealer, afterDealer)       = afterNonDealer.splitAt(HandSize)
    val (upcard, stock)             = afterDealer.splitAt(1)

    InProgress(
      Table(stock, upcard, Hands(Hand.of(dealer), Hand.of(nonDealer))),
      Phase.UpcardOffered(Player.NonDealer)
    )
  }

  /** This move played by this player, or why it was refused. */
  def apply(state: GameState, player: Player, move: Move): Either[GameError, GameState] = {
    state match {
      case _: Finished              => Left(GameError.RoundOver)
      case InProgress(table, phase) =>
        if (player =!= phase.onTurn) Left(GameError.NotYourTurn)
        else step(table, phase, move)
    }
  }

  private def step(table: Table, phase: Phase, move: Move): Either[GameError, GameState] = {
    (phase, move) match {
      case (Phase.UpcardOffered(player), Move.DrawDiscard)    => takeUpcard(table, player)
      case (Phase.UpcardOffered(Player.NonDealer), Move.Pass) =>
        Right(InProgress(table, Phase.UpcardOffered(Player.Dealer)))
      case (Phase.UpcardOffered(Player.Dealer), Move.Pass) =>
        Right(InProgress(table, Phase.AwaitingOpeningDraw))
      case (Phase.UpcardOffered(_), Move.DrawStock) => Left(GameError.StockClosed)
      case (Phase.UpcardOffered(_), _)              => Left(GameError.MustDraw)

      case (Phase.AwaitingOpeningDraw, Move.DrawStock)   => drawStock(table, Player.NonDealer)
      case (Phase.AwaitingOpeningDraw, Move.DrawDiscard) => Left(GameError.PileClosed)
      case (Phase.AwaitingOpeningDraw, Move.Pass)        => Left(GameError.NothingToPass)
      case (Phase.AwaitingOpeningDraw, _)                => Left(GameError.MustDraw)

      case (Phase.AwaitingDraw(player), Move.DrawStock)   => drawStock(table, player)
      case (Phase.AwaitingDraw(player), Move.DrawDiscard) => takeUpcard(table, player)
      case (Phase.AwaitingDraw(_), Move.Pass)             => Left(GameError.NothingToPass)
      case (Phase.AwaitingDraw(_), _)                     => Left(GameError.MustDraw)

      case (Phase.AwaitingDiscard(player, taken), Move.Discard(card)) =>
        discard(table, player, taken, card, ending = false)
      case (Phase.AwaitingDiscard(player, taken), Move.Knock(card)) =>
        discard(table, player, taken, card, ending = true)
      case (Phase.AwaitingDiscard(_, _), Move.Pass) => Left(GameError.NothingToPass)
      case (Phase.AwaitingDiscard(_, _), _)         => Left(GameError.MustDiscard)
    }
  }

  /** The pile is empty only between a take and the discard that follows it, and no draw is legal
    * there, so the refusal below is unreachable rather than a rule of its own.
    */
  private def takeUpcard(table: Table, player: Player): Either[GameError, GameState] = {
    table.discard match {
      case Nil          => Left(GameError.PileClosed)
      case card :: rest =>
        Right(
          InProgress(
            held(table.copy(discard = rest), player, card),
            Phase.AwaitingDiscard(player, Some(card))
          )
        )
    }
  }

  /** A round dies on the discard that leaves [[StockFloor]] cards, so a draw never finds the stock
    * empty and the refusal below is unreachable in the same way.
    */
  private def drawStock(table: Table, player: Player): Either[GameError, GameState] = {
    table.stock match {
      case Nil          => Left(GameError.StockClosed)
      case card :: rest =>
        Right(
          InProgress(
            held(table.copy(stock = rest), player, card),
            Phase.AwaitingDiscard(player, None)
          )
        )
    }
  }

  private def held(table: Table, player: Player, card: Card): Table =
    table.copy(hands = table.hands.updated(player, table.hands(player).add(card)))

  private def discard(
      table: Table,
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

          if (ending) knock(settled, player, hand)
          else if (settled.stock.size <= StockFloor) Right(Finished(settled, Outcome.Dead))
          else Right(InProgress(settled, Phase.AwaitingDraw(player.other)))
        }
      }
    }
  }

  private def knock(table: Table, player: Player, hand: Hand): Either[GameError, GameState] = {
    val deadwood = hand.deadwoodValue

    if (deadwood > KnockThreshold) Left(GameError.CannotKnock(deadwood))
    else Right(Finished(table, Outcome.Knocked(player)))
  }

  given Eq[GameState] = Eq.fromUniversalEquals

  given Show[GameState] = Show.show {
    case InProgress(table, phase) => show"InProgress($phase, $table)"
    case Finished(table, outcome) => show"Finished($outcome, $table)"
  }
}
