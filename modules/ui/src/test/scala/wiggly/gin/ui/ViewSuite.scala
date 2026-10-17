package wiggly.gin.ui

import cats.Show
import org.scalacheck.Gen
import tyrian.*
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.*
import wiggly.gin.core.port.Credentials
import wiggly.gin.gen.ViewGen
import wiggly.gin.gen.ViewGen.held

object ViewSuite extends SimpleIOSuite with Checkers {

  private given Show[PlayerView.InPlay] = Show.fromToString

  private val seat = Credentials(GameId("g1"), Player.One, Token("t1"))

  private val waiting = new PlayerView.AwaitingOpponent(GameId("g1"), Player.One)

  pureTest("the start screen offers both ways in") {
    val rendered = drawn(Model.Start("", None))

    expect(rendered.contains("Create a game")).and(expect(rendered.contains("Join")))
  }

  pureTest("the waiting screen shows the game to share") {
    expect(drawn(Model.Seated(seat, waiting, None)).contains("g1"))
  }

  pureTest("an error is put in front of the player") {
    val rendered = drawn(Model.Seated(seat, waiting, Some("that is not your turn")))

    expect(rendered.contains("that is not your turn"))
  }

  pureTest("a token is never drawn on any screen") {
    val rendered = drawn(Model.Seated(seat, waiting, None)) + drawn(Model.Connecting(seat))

    expect(!rendered.contains("t1"))
  }

  test("every card in the hand is drawn with its name") {
    forall(ViewGen.inPlay) { view =>
      val rendered = drawn(playing(view))

      expect(held(view).forall(card => rendered.contains(s"""aria-label="${Cards.name(card)}"""")))
    }
  }

  test("the other hand is drawn face down, one back for each card, beside the stock") {
    forall(ViewGen.inPlay) { view =>
      val backs = """aria-label="a face-down card"""".r.findAllIn(drawn(playing(view))).size

      expect.same(view.opponentSize + 1, backs)
    }
  }

  private val views: Gen[PlayerView.InPlay] = Gen.oneOf(ViewGen.inPlay, ViewGen.discarding)

  private def clicks(model: Model): List[Msg] = Handlers.sent(View(model), "click")

  private def chosen(view: PlayerView.InPlay): Option[Holding] = {
    Moves.available(view).collectFirst { case Move.Discard(card) => card }.map { card =>
      Holding.of(view).toggled(card, view)
    }
  }

  test("a pile can be clicked exactly when drawing from it is a move") {
    forall(views) { view =>
      val sent      = clicks(playing(view))
      val available = Moves.available(view)

      expect
        .same(available.contains(Move.DrawStock), sent.contains(Msg.Play(Move.DrawStock)))
        .and(
          expect.same(
            available.contains(Move.DrawDiscard),
            sent.contains(Msg.Play(Move.DrawDiscard))
          )
        )
    }
  }

  test("a card in the hand can be clicked exactly when it is a legal discard") {
    forall(views) { view =>
      val toggles = clicks(playing(view)).collect { case Msg.Toggle(card) => card }.toSet
      val legal   = Moves.available(view).collect { case Move.Discard(card) => card }.toSet

      expect.same(legal, toggles)
    }
  }

  test("with a card selected, the buttons are its discard and, when it is legal, its knock") {
    forall(ViewGen.discarding) { view =>
      chosen(view) match {
        case None          => success
        case Some(holding) => {
          val card   = holding.selected.toList
          val acting = clicks(Model.Playing(seat, view, holding, None)).collect {
            case Msg.Play(move @ (Move.Discard(_) | Move.Knock(_))) => move
          }
          val expected = card
            .flatMap(c => List(Move.Discard(c), Move.Knock(c)))
            .filter(Moves.available(view).contains)

          expect.same(expected, acting)
        }
      }
    }
  }

  test("with no card selected, a discard waits for one") {
    forall(ViewGen.discarding) { view =>
      val model  = playing(view)
      val acting = clicks(model).collect {
        case Msg.Play(move @ (Move.Discard(_) | Move.Knock(_))) => move
      }

      expect(drawn(model).contains("Select a card to discard")).and(expect(acting.isEmpty))
    }
  }

  test("each meld has its own tint, and deadwood has none") {
    forall(ViewGen.inPlay) { view =>
      val slots = """(?s)class="slot(?: ([^"]*))?".*?aria-label="([^"]*)"""".r
      val found = slots
        .findAllMatchIn(drawn(playing(view)))
        .map(m => m.group(2) -> Option(m.group(1)))
        .toMap
      val expected = view.arrangement.melds.zipWithIndex.flatMap { case (meld, i) =>
        meld.cards.toList.map(card => Cards.name(card) -> Option(s"meld-$i"))
      }.toMap ++ view.arrangement.deadwood.map(card => Cards.name(card) -> Option.empty[String])

      expect.same(expected, found)
    }
  }

  test("the hand is drawn in the holding's order") {
    forall(ViewGen.inPlay) { view =>
      val holding = Holding.of(view).sorted(Sorting.ByRank, view)
      val names   = held(view).map(Cards.name).toSet
      val drawnIn = """aria-label="([^"]*)"""".r
        .findAllMatchIn(View(Model.Playing(seat, view, holding, None)).render)
        .map(_.group(1))
        .filter(names.contains)
        .toList

      expect.same(holding.order.map(Cards.name), drawnIn)
    }
  }

  test("the sort buttons are always there") {
    forall(ViewGen.inPlay) { view =>
      val sorts = clicks(playing(view)).collect { case Msg.Sort(by) => by }

      expect.same(Sorting.values.toList, sorts)
    }
  }

  private def playing(view: PlayerView.InPlay): Model =
    Model.Playing(seat, view, Holding.of(view), None)

  private def drawn(model: Model): String = View(model).render
}
