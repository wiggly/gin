package wiggly.gin.core.domain

import cats.syntax.all.*
import cats.{Eq, Show}

/** A hand split into the melds it makes and the cards left over.
  *
  * The leftovers are what the hand is worth against its holder, which is what knocking, gin and
  * every score in the game are measured in.
  */
final case class Arrangement(melds: List[Meld], deadwood: List[Card]) {
  def deadwoodValue: Int = deadwood.map(_.deadwoodValue).sum
}

object Arrangement {

  /** The arrangement of these cards that leaves the least deadwood.
    *
    * Total over any number of cards rather than only the ten or eleven of a real hand: asking what
    * a handful of cards is worth is a reasonable question, and it keeps worked examples small.
    * Cards are expected to be distinct, since a hand is dealt from a single deck; a repeated card
    * is not rejected, it simply ends up as deadwood.
    *
    * Brute force, and deliberately so — a hand is small enough that a correct slow answer beats a
    * clever wrong one. If a profile ever disagrees, the search below memoises on the cards still
    * available.
    */
  def best(hand: List[Card]): Arrangement = {
    val cards = hand.sorted
    val melds = select(cards, candidatesWithin(cards))

    Arrangement(melds, cards.diff(melds.flatMap(_.cards.toList)))
  }

  /** Every valid meld that can be made from these cards, in canonical order.
    *
    * The order matters: it is what decides between two arrangements that leave the same deadwood,
    * and so what makes [[best]] reproducible rather than a matter of which meld happened to be
    * enumerated first.
    */
  private def candidatesWithin(cards: List[Card]): List[Meld] =
    (setsWithin(cards) ++ runsWithin(cards)).distinct.sorted

  /** Sets are enumerated as every *combination* of three or more cards of a rank, not only the
    * whole group. Which three of four sevens a set uses looks like it cannot matter, since they are
    * worth the same — but the one left out is free to join a run, and dropping the wrong one hides
    * the arrangement that melds everything.
    */
  private def setsWithin(cards: List[Card]): List[Meld] =
    groupsOf(cards.groupBy(_.rank))(group => size => group.combinations(size))
      .flatMap(Meld.Set.from)

  /** Runs are enumerated as every *window* of three or more cards in a suit, not only the longest
    * one — a run has to be contiguous, so windows are the whole space. Taking only maximal runs is
    * wrong: `4D 5D 6D 7D` alongside `7H 7C` is gin, because the run gives up its seven to the set,
    * and a maximal-runs-only search never sees that split.
    */
  private def runsWithin(cards: List[Card]): List[Meld] =
    groupsOf(cards.groupBy(_.suit))(group => size => group.sliding(size))
      .flatMap(Meld.Run.from)

  private def groupsOf[A](
      grouped: Map[A, List[Card]]
  )(subsets: List[Card] => Int => Iterator[List[Card]]): List[List[Card]] =
    grouped.values.toList.flatMap { group =>
      val sorted = group.sorted

      (Meld.MinimumSize to sorted.size).toList.flatMap(size => subsets(sorted)(size).toList)
    }

  /** Works down the available cards in canonical order. The lowest card is either deadwood or part
    * of one of the melds that can still be made from it, so each branch either commits a card to
    * the deadwood or commits a whole meld, and the search ends when no cards are left.
    *
    * The best branch is the one melding the most value, which is the same thing as leaving the
    * least. `maxBy` keeps the first of equal branches, so a tie goes to the earliest candidate in
    * canonical order, and melding beats leaving a card loose.
    */
  private def select(available: List[Card], candidates: List[Meld]): List[Meld] =
    available match {
      case Nil          => Nil
      case card :: rest => {
        val usable = candidates.filter { meld =>
          val cards = meld.cards.toList

          cards.contains_(card) && cards.forall(available.contains_)
        }

        val melding = usable.map { meld =>
          meld :: select(available.diff(meld.cards.toList), candidates)
        }

        (melding :+ select(rest, candidates)).maxBy(meldedValue)
      }
    }

  private def meldedValue(melds: List[Meld]): Int = melds.map(_.deadwoodValue).sum

  given Eq[Arrangement] = Eq.fromUniversalEquals

  given Show[Arrangement] = Show.show { arrangement =>
    val melds    = arrangement.melds.map(_.show).mkString("; ")
    val deadwood = arrangement.deadwood.map(_.show).mkString(", ")

    s"Arrangement(melds = [$melds], deadwood = [$deadwood] worth ${arrangement.deadwoodValue})"
  }
}
