package wiggly.gin.core.domain

import cats.data.NonEmptyList
import cats.syntax.all.*
import cats.{Eq, Order, Show}

/** A defender's hand once a knock has put the knocker's melds on the table: the melds the defender
  * makes, the cards they put onto the knocker's melds, and what is left to be counted against
  * them.
  *
  * The three lists together are the whole hand. A laid-off card belongs to neither list of an
  * [[Arrangement]], which is why this is a type of its own rather than an arrangement with a
  * longer meld in it.
  */
final case class Defence(melds: List[Meld], layoffs: List[Card], deadwood: List[Card]) {
  def deadwoodValue: Int = deadwood.map(_.deadwoodValue).sum
}

object Defence {

  /** What this hand is left holding against the melds a knocker laid down.
    *
    * Every layoff that can be made is made, because a layoff only ever cuts deadwood, so a
    * defender offered one would never decline it.
    *
    * Melding a card and laying it off both cost the holder nothing, so the two compete, and they
    * are chosen in one search rather than in two passes. The card that decides it is the one
    * joining the defender's hand to the end of a knocker's run: a first pass that melded it would
    * strand every card behind it, and would have no way to reconsider once the layoffs were known.
    */
  def against(hand: Hand, laid: List[Meld]): Defence = {
    val cards      = hand.cards.sorted
    val candidates = Arrangement.candidatesWithin(cards).map(Own(_)) ++ onto(laid, cards)
    val taken      = Arrangement.select(cards, candidates)(_.cards)

    val melds   = taken.collect { case Own(meld) => meld }
    val layoffs = taken.collect { case Onto(cards) => cards.toList }.flatten.sorted

    Defence(melds, layoffs, cards.diff(melds.flatMap(_.cards.toList) ++ layoffs))
  }

  /** Cards the defender can zero together: a meld of their own, or a group that goes onto one meld
    * the knocker laid down.
    */
  private sealed trait Candidate {
    def cards: NonEmptyList[Card]
  }

  private final case class Own(meld: Meld) extends Candidate {
    def cards: NonEmptyList[Card] = meld.cards
  }

  private final case class Onto(cards: NonEmptyList[Card]) extends Candidate

  /** Every group of these cards that can go onto one of the laid melds, in canonical order.
    *
    * Melds come first in [[against]] and these after them, so that two answers worth the same put
    * a card in the defender's own meld rather than on the knocker's, which is what the table would
    * look like.
    */
  private def onto(laid: List[Meld], cards: List[Card]): List[Candidate] = {
    val groups = laid.flatMap {
      case set: Meld.Set => cards.filter(_.rank === set.rank).map(NonEmptyList.one)
      case run: Meld.Run => {
        val suited = cards.filter(_.suit === run.suit)

        prefixes(above(suited, run.highest)) ++ prefixes(below(suited, run.lowest))
      }
    }

    groups.distinct.sorted(using Order[NonEmptyList[Card]].toOrdering).map(Onto(_))
  }

  /** A layoff is contiguous with the run it joins, so a defender who lays off a card must lay off
    * every card between it and the run. Each prefix of a chain is therefore a group of its own,
    * and a defender is free to stop part way along one.
    */
  private def prefixes(chain: List[Card]): List[NonEmptyList[Card]] =
    chain.indices.toList.flatMap(size => NonEmptyList.fromList(chain.take(size + 1)))

  private def above(suited: List[Card], from: Rank): List[Card] =
    from.next.flatMap(rank => suited.find(_.rank === rank)) match {
      case None       => Nil
      case Some(card) => card :: above(suited, card.rank)
    }

  /** Walks down by asking which card steps up to the one before it, because a rank knows the rank
    * above it and a run never wraps, so there is nothing to subtract from.
    */
  private def below(suited: List[Card], from: Rank): List[Card] =
    suited.find(_.rank.next.contains_(from)) match {
      case None       => Nil
      case Some(card) => card :: below(suited, card.rank)
    }

  given Eq[Defence] = Eq.fromUniversalEquals

  given Show[Defence] = Show.show { defence =>
    val melds    = defence.melds.map(_.show).mkString("; ")
    val layoffs  = defence.layoffs.map(_.show).mkString(", ")
    val deadwood = defence.deadwood.map(_.show).mkString(", ")

    s"Defence(melds = [$melds], laid off = [$layoffs], deadwood = [$deadwood] worth ${defence.deadwoodValue})"
  }
}
