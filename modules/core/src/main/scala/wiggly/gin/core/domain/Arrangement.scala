package wiggly.gin.core.domain

import cats.data.NonEmptyList
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
    * Cards are distinct, because a hand comes from a [[Deck]] and no move afterwards ever makes a
    * card. A repeated card is not rejected and simply ends up as deadwood, but nothing in the game
    * can hand one over.
    *
    * Brute force, and deliberately so — a hand is small enough that a correct slow answer beats a
    * clever wrong one. If a profile ever disagrees, the search below memoises on the cards still
    * available.
    */
  def best(hand: List[Card]): Arrangement = {
    val cards = hand.sorted
    val melds = select(cards, candidatesWithin(cards))(_.cards)

    Arrangement(melds, cards.diff(melds.flatMap(_.cards.toList)))
  }

  /** Every valid meld that can be made from these cards, in canonical order.
    *
    * The order matters: it is what decides between two arrangements that leave the same deadwood,
    * and so what makes [[best]] reproducible rather than a matter of which meld happened to be
    * enumerated first.
    *
    * [[Defence]] runs the same search with these candidates and the layoffs beside them, which is
    * why this and [[select]] are open to the rest of the domain.
    */
  private[domain] def candidatesWithin(cards: List[Card]): List[Meld] =
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
    * of one of the candidates that can still be taken with it, so each branch either commits a
    * card to the deadwood or commits a whole candidate, and the search ends when no cards are
    * left.
    *
    * The best branch is the one that costs its holder the least, which is the same thing as
    * zeroing the most. `maxBy` keeps the first of equal branches, so a tie goes to the earliest
    * candidate in canonical order, and taking a candidate beats leaving a card loose.
    *
    * A candidate is any group of cards that can be zeroed together. For [[best]] that is a meld.
    * [[Defence]] passes layoffs as well, so that the choice between melding a card and laying it
    * off is made in one search rather than by a first pass that cannot see the second.
    */
  private[domain] def select[A](available: List[Card], candidates: List[A])(
      cardsOf: A => NonEmptyList[Card]
  ): List[A] =
    available match {
      case Nil          => Nil
      case card :: rest => {
        val usable = candidates.filter { candidate =>
          val cards = cardsOf(candidate).toList

          cards.contains_(card) && cards.forall(available.contains_)
        }

        val taking = usable.map { candidate =>
          candidate :: select(available.diff(cardsOf(candidate).toList), candidates)(cardsOf)
        }

        (taking :+ select(rest, candidates)(cardsOf)).maxBy(zeroedValue(cardsOf))
      }
    }

  private def zeroedValue[A](cardsOf: A => NonEmptyList[Card])(taken: List[A]): Int =
    taken.flatMap(cardsOf(_).toList).map(_.deadwoodValue).sum

  given Eq[Arrangement] = Eq.fromUniversalEquals

  given Show[Arrangement] = Show.show { arrangement =>
    val melds    = arrangement.melds.map(_.show).mkString("; ")
    val deadwood = arrangement.deadwood.map(_.show).mkString(", ")

    s"Arrangement(melds = [$melds], deadwood = [$deadwood] worth ${arrangement.deadwoodValue})"
  }
}
