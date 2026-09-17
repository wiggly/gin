package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.domain.{Card, Meld, Rank, Suit}

object MeldGen {

  /** Three or four cards of one rank. */
  val set: Gen[Meld.Set] = for {
    rank  <- CardGen.rank
    size  <- Gen.choose(Meld.MinimumSize, Suit.values.length)
    suits <- Gen.pick(size, Suit.values.toList)
  } yield built(Meld.Set.from(suits.toList.map(Card(rank, _))))

  /** A run of a given length, anywhere in the thirteen ranks of one suit. */
  def runOfLength(length: Int): Gen[Meld.Run] = for {
    suit <- CardGen.suit
    from <- Gen.choose(0, Rank.values.length - length)
    ranks = Rank.values.toList.slice(from, from + length)
  } yield built(Meld.Run.from(ranks.map(Card(_, suit))))

  /** A run of three or more cards, up to the whole suit. */
  val run: Gen[Meld.Run] = Gen.choose(Meld.MinimumSize, Rank.values.length).flatMap(runOfLength)

  val meld: Gen[Meld] = Gen.oneOf(set, run)

  /** A meld of three or four cards: the only size a ten-card hand can hold several of, so this is
    * what a generated hand is built out of.
    */
  val shortMeld: Gen[Meld] =
    Gen.oneOf(set, Gen.choose(Meld.MinimumSize, Meld.MinimumSize + 1).flatMap(runOfLength))

  /** Generators build melds through the same smart constructors as production code, so a generator
    * that produces something invalid is a bug worth hearing about loudly rather than discarding.
    */
  private def built[A](meld: Option[A]): A =
    meld.getOrElse(sys.error("generator produced cards that do not form a meld"))
}
