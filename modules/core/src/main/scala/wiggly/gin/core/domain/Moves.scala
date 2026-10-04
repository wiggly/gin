package wiggly.gin.core.domain

import cats.syntax.all.*

/** What a player may do, answered from what that player can see.
  *
  * This is the rule the client and the server have to agree on, so it is stated here once rather
  * than a second time in a browser. A redacted view carries enough to settle it: nothing a player
  * is kept from seeing bears on which of their own moves is legal.
  */
object Moves {

  def available(view: PlayerView.InPlay): List[Move] = {
    if (view.you =!= view.onTurn) {
      Nil
    } else {
      view.phase match {
        case Phase.UpcardOffered(_)          => List(Move.DrawDiscard, Move.Pass)
        case Phase.AwaitingOpeningDraw       => List(Move.DrawStock)
        case Phase.AwaitingDraw(_)           => List(Move.DrawStock, Move.DrawDiscard)
        case Phase.AwaitingDiscard(_, taken) => discards(view.arrangement, taken)
      }
    }
  }

  private def discards(held: Arrangement, taken: Option[Card]): List[Move] = {
    val cards = held.melds.flatMap(_.cards.toList) ++ held.deadwood

    cards.sorted.filterNot(taken.contains).flatMap { card =>
      val kept  = remove(cards, card)
      val knock = Option.when(
        Arrangement.best(kept).deadwoodValue <= GameState.KnockThreshold
      )(Move.Knock(card))

      Move.Discard(card) :: knock.toList
    }
  }

  /** One copy, not every copy. A hand holds a card once, but taking every copy out would be a
    * different rule from the one the round applies, and the two must not drift apart.
    */
  private def remove(cards: List[Card], card: Card): List[Card] = {
    cards.span(_ =!= card) match {
      case (before, _ :: after) => before ++ after
      case (all, Nil)           => all
    }
  }
}
