package wiggly.gin.core.service

import cats.effect.IO
import cats.implicits.*
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.*
import wiggly.gin.core.port.{Credentials, GameService}
import wiggly.gin.fake.{CountingSecrets, FakeEvents, FakeRepository, FixedShuffler}
import wiggly.gin.gen.GameGen

object GamesSuite extends SimpleIOSuite with Checkers {

  private final case class Table(
      service: GameService[IO],
      repository: FakeRepository[IO],
      events: FakeEvents[IO]
  )

  private def table(deck: Deck = Deck.ordered): IO[Table] = for {
    repository <- FakeRepository[IO]
    events     <- FakeEvents[IO]
    secrets    <- CountingSecrets[IO]
  } yield Table(
    Games[IO](repository, events, new FixedShuffler[IO](deck), secrets),
    repository,
    events
  )

  /** A game created and joined, with what each player was given to claim their seat. */
  private def seated(deck: Deck = Deck.ordered): IO[(Table, Credentials, Credentials)] = for {
    table <- table(deck)
    host  <- table.service.create
    guest <- table.service
      .join(host.id)
      .map(_.getOrElse(sys.error("a game with one player refused a second")))
  } yield (table, host, guest)

  private def inPlay(view: PlayerView): PlayerView.InPlay = {
    view match {
      case playing: PlayerView.InPlay => playing
      case other                      => sys.error(s"the view is not of a round in play: $other")
    }
  }

  private def cardsOf(arrangement: Arrangement): List[Card] =
    arrangement.melds.flatMap(_.cards.toList) ++ arrangement.deadwood

  test("creating a game stores one waiting for an opponent and names the host") {
    for {
      table  <- table()
      host   <- table.service.create
      stored <- table.repository.stored
    } yield expect.eql(host.you, Player.One) and
      expect.eql(stored.get(host.id), Some(Game.AwaitingOpponent(host.id, host.token)))
  }

  test("creating a game publishes it, so a host watching hears the game start") {
    for {
      table     <- table()
      host      <- table.service.create
      published <- table.events.published
    } yield expect.eql(published.map(_.id), List(host.id))
  }

  test("joining names the second player and deals the first round") {
    for {
      (table, host, guest) <- seated()
      view                 <- table.service.look(host.id, host.token)
    } yield expect.eql(guest.you, Player.Two) and
      expect.eql(guest.id, host.id) and
      expect.eql(view.map(inPlay(_).onTurn), Right(Player.One))
  }

  test("joining a game nobody created is refused") {
    table().flatMap(_.service.join(GameId("no-such-game"))).map { joined =>
      expect.eql(joined, Left(GameFault.NoSuchGame))
    }
  }

  test("a third player is turned away") {
    for {
      (table, host, _) <- seated()
      third            <- table.service.join(host.id)
    } yield expect.eql(third, Left(GameFault.AlreadyFull))
  }

  test("a token neither player holds is refused, whether it is looking, playing or watching") {
    for {
      (table, host, _) <- seated()
      looked           <- table.service.look(host.id, Token("nobody"))
      played           <- table.service.play(host.id, Token("nobody"), Move.DrawDiscard)
      watched          <- table.service.watch(host.id, Token("nobody"))
    } yield expect.eql(looked, Left(GameFault.NotAPlayer)) and
      expect.eql(played, Left(GameFault.NotAPlayer)) and
      expect(watched.isLeft)
  }

  test("looking at a game nobody created is refused") {
    table().flatMap(_.service.look(GameId("no-such-game"), Token("any"))).map { looked =>
      expect.eql(looked, Left(GameFault.NoSuchGame))
    }
  }

  test("each player is shown their own hand and only a count of the other") {
    for {
      (table, host, guest) <- seated()
      theirs               <- table.service.look(host.id, host.token)
      others               <- table.service.look(guest.id, guest.token)
    } yield expect.eql(theirs.map(inPlay(_).you), Right(Player.One)) and
      expect.eql(others.map(inPlay(_).you), Right(Player.Two)) and
      expect.eql(theirs.map(inPlay(_).opponentSize), Right(GameState.HandSize)) and
      expect(theirs.map(inPlay(_).arrangement) =!= others.map(inPlay(_).arrangement))
  }

  test("a move comes back as the mover's own view of what they did") {
    for {
      (table, host, _) <- seated()
      played           <- table.service.play(host.id, host.token, Move.DrawDiscard)
    } yield expect.eql(played.map(inPlay(_).you), Right(Player.One)) and
      expect.eql(
        played.map(view => cardsOf(inPlay(view).arrangement).size),
        Right(GameState.HandSize + 1)
      )
  }

  test("a move publishes the game, so the other player hears it") {
    for {
      (table, host, _) <- seated()
      _                <- table.service.play(host.id, host.token, Move.DrawDiscard)
      published        <- table.events.published
    } yield expect.eql(published.size, 3)
  }

  test("a move the rules refuse comes back carrying the rule's own error") {
    for {
      (table, _, guest) <- seated()
      played            <- table.service.play(guest.id, guest.token, Move.DrawDiscard)
    } yield expect.eql(played, Left(GameFault.Illegal(GameError.NotYourTurn)))
  }

  test("a refused move leaves the stored game exactly as it was") {
    for {
      (table, host, guest) <- seated()
      before               <- table.repository.read(host.id)
      _                    <- table.service.play(guest.id, guest.token, Move.DrawDiscard)
      after                <- table.repository.read(host.id)
    } yield expect.eql(before, after)
  }

  test("watching hands back that player's own views of the game") {
    for {
      (table, host, _) <- seated()
      watched          <- table.service.watch(host.id, host.token)
      seen             <- watched.toOption.traverse(_.compile.toList)
    } yield expect(seen.exists(_.nonEmpty)) and
      expect(seen.toList.flatten.forall {
        case waiting: PlayerView.AwaitingOpponent => waiting.you === Player.One
        case playing: PlayerView.InPlay           => playing.you === Player.One
        case over: PlayerView.Over                => over.you === Player.One
      })
  }

  test("every legal move through the service leaves a game holding one whole deck") {
    forall(Gen.listOfN(12, Gen.choose(0, 63))) { choices =>
      for {
        (table, host, _) <- seated()
        _                <- choices.traverse_(choice => playOneOf(table, host.id, choice))
        stored           <- table.repository.read(host.id)
      } yield expect(stored.forall {
        case playing: Game.InPlay => GameGen.allCards(playing.round).sorted === Deck.ordered.cards
        case _                    => true
      })
    }
  }

  /** Plays whichever legal move the number lands on, asking the stored game what is legal rather
    * than deciding here, so the walk cannot agree with a second copy of the rules.
    */
  private def playOneOf(table: Table, id: GameId, choice: Int): IO[Unit] = {
    table.repository.read(id).flatMap {
      case Some(playing: Game.InPlay) => {
        val moves     = GameGen.legalMoves(playing.round)
        val (move, _) = moves(choice % moves.size)
        val player    = playing.round.onTurn

        table.service.play(id, playing.tokens(player), move).void
      }
      case _ => IO.unit
    }
  }
}
