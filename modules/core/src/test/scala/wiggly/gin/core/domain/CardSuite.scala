package wiggly.gin.core.domain

import cats.implicits.*
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.gen.CardGen

object CardSuite extends SimpleIOSuite with Checkers {

  test("a card is worth whatever its rank is worth, whatever its suit") {
    forall(CardGen.card) { card =>
      expect.eql(card.deadwoodValue, card.rank.deadwoodValue)
    }
  }

  test("two cards compare equal only when they are the same card") {
    forall(Gen.zip(CardGen.card, CardGen.card)) { (left, right) =>
      expect.eql(left.compare(right) == 0, left === right)
    }
  }

  test("the canonical order agrees with the order the deck is built in") {
    forall(CardGen.distinctCards(52)) { shuffled =>
      expect.eql(shuffled.sorted, Deck.ordered.cards)
    }
  }
}
