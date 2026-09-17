package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.domain.{Card, Deck, Meld}

object HandGen {

  /** The cards a player holds between turns. Eleven is the same hand mid-turn, after a draw and
    * before the discard.
    */
  val HandSize: Int = 10

  val hand: Gen[List[Card]] = CardGen.distinctCards(HandSize)

  /** A hand built from melds chosen up front, paired with the deadwood that construction leaves.
    *
    * The point is that the number comes from outside the code under test: it is an arrangement that
    * demonstrably exists, so the best arrangement can never be worth more than it. It is an upper
    * bound rather than the answer, because the cards filling out the hand are free to form melds of
    * their own or extend the ones already chosen.
    */
  def handWithKnownDeadwood(size: Int = HandSize): Gen[(List[Card], Int)] = for {
    offered <- Gen.listOfN(3, MeldGen.shortMeld)
    melded = disjointWithin(offered, size).flatMap(_.cards.toList)
    loose <- Gen.pick(size - melded.size, Deck.ordered.diff(melded))
  } yield (melded ++ loose, loose.toList.map(_.deadwoodValue).sum)

  /** Keeps the melds that neither overlap one already kept nor overflow the hand. */
  private def disjointWithin(melds: List[Meld], size: Int): List[Meld] =
    melds.foldLeft(List.empty[Meld]) { (kept, meld) =>
      val used  = kept.flatMap(_.cards.toList)
      val cards = meld.cards.toList

      if (cards.forall(card => !used.contains(card)) && used.size + cards.size <= size) kept :+ meld
      else kept
    }
}
