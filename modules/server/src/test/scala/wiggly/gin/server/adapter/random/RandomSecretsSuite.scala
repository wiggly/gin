package wiggly.gin.server.adapter.random

import cats.effect.IO
import cats.implicits.*
import weaver.SimpleIOSuite
import wiggly.gin.core.domain.Deck

object RandomSecretsSuite extends SimpleIOSuite {

  private val draws = 2000

  test("a thousand tokens are a thousand different tokens") {
    for {
      secrets <- RandomSecrets[IO]
      tokens  <- secrets.token.replicateA(draws)
    } yield expect.eql(tokens.distinct.size, draws)
  }

  test("a thousand game ids are a thousand different ids") {
    for {
      secrets <- RandomSecrets[IO]
      ids     <- secrets.gameId.replicateA(draws)
    } yield expect.eql(ids.distinct.size, draws)
  }

  test("a shuffled deck is always a deck") {
    for {
      shuffler <- RandomShuffler[IO]
      decks    <- shuffler.shuffled.replicateA(50)
    } yield expect(decks.forall(deck => deck.cards.sorted === Deck.ordered.cards))
  }

  test("a shuffle does not hand back the deck it was given") {
    for {
      shuffler <- RandomShuffler[IO]
      decks    <- shuffler.shuffled.replicateA(20)
    } yield expect(decks.exists(_.cards =!= Deck.ordered.cards)) and
      expect(decks.map(_.cards).distinct.size > 1)
  }
}
