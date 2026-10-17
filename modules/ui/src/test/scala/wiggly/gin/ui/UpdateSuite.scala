package wiggly.gin.ui

import cats.Show
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers
import wiggly.gin.core.domain.*
import wiggly.gin.core.port.Credentials
import wiggly.gin.gen.ViewGen
import wiggly.gin.gen.ViewGen.held

object UpdateSuite extends SimpleIOSuite with Checkers {

  private given Show[PlayerView.InPlay] = Show.fromToString
  private given Show[Msg]               = Show.fromToString

  private val seat = Credentials(GameId("g1"), Player.One, Token("t1"))

  private val waiting = new PlayerView.AwaitingOpponent(GameId("g1"), Player.One)

  private val handMessages: Gen[(PlayerView.InPlay, Card, Msg)] = for {
    view <- Gen.oneOf(ViewGen.inPlay, ViewGen.discarding)
    card <- Gen.oneOf(held(view))
    msg  <- Gen.oneOf(
      Gen.const(Msg.Toggle(card)),
      Gen.oneOf(Sorting.values.toList).map(Msg.Sort(_)),
      Gen.const(Msg.Lift(card)),
      Gen.const(Msg.Drop(Some(card))),
      Gen.const(Msg.Drop(None)),
      Gen.const(Msg.Hover)
    )
  } yield (view, card, msg)

  private def chosen(view: PlayerView.InPlay): Holding = {
    val first = Moves.available(view).collectFirst { case Move.Discard(card) => card }

    first.fold(Holding.of(view))(Holding.of(view).toggled(_, view))
  }

  pureTest("taking a seat moves off the start screen") {
    val (model, _) = Update(Model.Start("", None), Msg.Took(seat))
    expect.same(Model.Connecting(seat), model)
  }

  pureTest("the first view seats the player") {
    val (model, _) = Update(Model.Connecting(seat), Msg.Saw(waiting))
    expect.same(Model.Seated(seat, waiting, None), model)
  }

  pureTest("a later view replaces the view and clears the last error") {
    val (model, _) = Update(Model.Seated(seat, waiting, Some("stale")), Msg.Saw(waiting))
    expect.same(Model.Seated(seat, waiting, None), model)
  }

  pureTest("a failure keeps the seat and shows the reason") {
    val (model, _) = Update(Model.Seated(seat, waiting, None), Msg.Failed("nope"))
    expect.same(Model.Seated(seat, waiting, Some("nope")), model)
  }

  pureTest("a failure before a seat exists stays on the start screen") {
    val (model, _) = Update(Model.Start("g2", None), Msg.Failed("nope"))
    expect.same(Model.Start("g2", Some("nope")), model)
  }

  pureTest("typing a game id is remembered") {
    val (model, _) = Update(Model.Start("", None), Msg.JoiningChanged("g2"))
    expect.same(Model.Start("g2", None), model)
  }

  test("a round in play seats the player at the table, in the server's order") {
    forall(ViewGen.inPlay) { view =>
      val (model, _) = Update(Model.Connecting(seat), Msg.Saw(view))
      expect.same(Model.Playing(seat, view, Holding.of(view), None), model)
    }
  }

  test("a round starting while the player waits moves them to the table") {
    forall(ViewGen.inPlay) { view =>
      val (model, _) = Update(Model.Seated(seat, waiting, None), Msg.Saw(view))
      expect.same(Model.Playing(seat, view, Holding.of(view), None), model)
    }
  }

  test("a later view keeps the order the player chose and clears the last error") {
    forall(ViewGen.turn) { case (before, after) =>
      val mine       = Holding.of(before).sorted(Sorting.ByRank, before)
      val (model, _) = Update(Model.Playing(seat, before, mine, Some("stale")), Msg.Saw(after))
      expect.same(Model.Playing(seat, after, mine.reconcile(after), None), model)
    }
  }

  test("a round that is no longer in play moves the player off the table and drops the holding") {
    forall(ViewGen.inPlay) { view =>
      val playing    = Model.Playing(seat, view, Holding.of(view), Some("stale"))
      val (model, _) = Update(playing, Msg.Saw(waiting))

      expect.same(Model.Seated(seat, waiting, None), model)
    }
  }

  test("a hand message changes the holding and nothing else") {
    forall(handMessages) { case (view, card, msg) =>
      val holding  = Holding.of(view).lifted(card)
      val expected = msg match {
        case Msg.Toggle(chosen) => holding.toggled(chosen, view)
        case Msg.Sort(by)       => holding.sorted(by, view)
        case Msg.Lift(lifted)   => holding.lifted(lifted)
        case Msg.Drop(before)   => holding.moved(before)
        case _                  => holding
      }
      val (model, _) = Update(Model.Playing(seat, view, holding, Some("kept")), msg)

      expect.same(Model.Playing(seat, view, expected, Some("kept")), model)
    }
  }

  test("a hand message away from the table changes nothing") {
    forall(handMessages) { case (_, _, msg) =>
      val away =
        List(Model.Start("g2", None), Model.Connecting(seat), Model.Seated(seat, waiting, None))

      expect(away.forall(model => Update(model, msg)._1 == model))
    }
  }

  test("a move keeps the holding and clears the last error") {
    forall(ViewGen.discarding) { view =>
      val holding    = chosen(view)
      val move       = Moves.available(view).head
      val (model, _) = Update(Model.Playing(seat, view, holding, Some("stale")), Msg.Play(move))

      expect.same(Model.Playing(seat, view, holding, None), model)
    }
  }

  test("a refused move keeps the selection") {
    forall(ViewGen.discarding) { view =>
      val holding    = chosen(view)
      val (model, _) = Update(Model.Playing(seat, view, holding, None), Msg.Failed("nope"))

      expect.same(Model.Playing(seat, view, holding, Some("nope")), model)
    }
  }
}
