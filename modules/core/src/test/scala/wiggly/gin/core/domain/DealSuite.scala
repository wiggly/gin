package wiggly.gin.core.domain

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.gen.GameGen

object DealSuite extends SimpleIOSuite with Checkers {

  test("the deal gives ten cards to each player") {
    forall(GameGen.dealt) { state =>
      expect.eql(state.table.hands(state.seats.dealer).size, 10) and
        expect.eql(state.table.hands(state.seats.nonDealer).size, 10)
    }
  }

  test("the deal turns one card up and leaves thirty-one in the stock") {
    forall(GameGen.dealt) { state =>
      expect.eql(state.table.discard.size, 1) and expect.eql(state.table.stock.size, 31)
    }
  }

  test("the deal puts every card somewhere and none of them twice") {
    forall(GameGen.dealt) { state =>
      expect.eql(GameGen.allCards(state).sorted, Deck.ordered.cards)
    }
  }

  test("a round opens with the upcard offered to the non-dealer") {
    forall(GameGen.dealt) { state =>
      expect.eql(GameGen.phase(state), Some(Phase.UpcardOffered(state.seats.nonDealer)))
    }
  }
}
