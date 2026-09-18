package wiggly.gin.core.domain

import cats.syntax.all.*
import cats.{Eq, Show}

/** The cards one player holds: ten between turns, eleven after drawing and before discarding.
  *
  * The count belongs to the phase of the round rather than to the hand, so nothing here enforces
  * it. What the hand does own is the one question every discard asks, which is whether the player
  * holds the card at all.
  */
final case class Hand private (cards: List[Card]) {

  def size: Int = cards.size

  def add(card: Card): Hand = new Hand((card :: cards).sorted)

  /** The hand without this card, or `None` if the hand does not hold it.
    *
    * Takes out one copy rather than every match. A hand dealt from a [[Deck]] cannot hold a card
    * twice, so the two are indistinguishable in a real round, which is exactly why the wrong one
    * would sit here unnoticed until something else went wrong.
    */
  def remove(card: Card): Option[Hand] = {
    val (before, after) = cards.span(_ =!= card)

    after match {
      case Nil           => None
      case _ :: leftover => Some(new Hand(before ++ leftover))
    }
  }

  def arrangement: Arrangement = Arrangement.best(cards)

  def deadwoodValue: Int = arrangement.deadwoodValue
}

object Hand {

  /** These cards as a hand, in canonical order.
    *
    * Total, and deliberately so. A hand on its own cannot tell whether it is legitimate: the
    * invariant that matters is that the stock, the pile and the two hands together are one deck,
    * which no single hand can see. [[Deck.from]] guards the way in and a property covers the rest.
    */
  def of(cards: List[Card]): Hand = new Hand(cards.sorted)

  given Eq[Hand] = Eq.fromUniversalEquals

  given Show[Hand] = Show.fromToString
}

/** Both players' hands, keyed by seat so that a lookup cannot fail. */
final case class Hands(dealer: Hand, nonDealer: Hand) {

  def apply(player: Player): Hand = {
    player match {
      case Player.Dealer    => dealer
      case Player.NonDealer => nonDealer
    }
  }

  def updated(player: Player, hand: Hand): Hands = {
    player match {
      case Player.Dealer    => copy(dealer = hand)
      case Player.NonDealer => copy(nonDealer = hand)
    }
  }

  def cards: List[Card] = dealer.cards ++ nonDealer.cards
}

object Hands {
  given Eq[Hands]   = Eq.fromUniversalEquals
  given Show[Hands] = Show.fromToString
}
