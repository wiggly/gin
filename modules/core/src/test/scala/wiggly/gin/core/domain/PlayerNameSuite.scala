package wiggly.gin.core.domain

import cats.implicits.*
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object PlayerNameSuite extends SimpleIOSuite with Checkers {

  private def letters(min: Int, max: Int): Gen[String] =
    Gen.choose(min, max).flatMap(Gen.listOfN(_, Gen.alphaChar)).map(_.mkString)

  test("a name of 1 to 20 letters is accepted as it was typed") {
    forall(letters(1, PlayerName.MaxLength)) { raw =>
      expect.eql(PlayerName.from(raw).map(_.value), Some(raw))
    }
  }

  test("space around a name is not part of it") {
    forall(letters(1, PlayerName.MaxLength)) { raw =>
      expect.eql(PlayerName.from(s"  $raw\t"), PlayerName.from(raw))
    }
  }

  test("a name longer than 20 is refused") {
    forall(letters(PlayerName.MaxLength + 1, 40)) { raw =>
      expect.eql(PlayerName.from(raw), None)
    }
  }

  test("a control character anywhere in a name refuses it") {
    val spoiled = for {
      raw     <- letters(2, PlayerName.MaxLength - 1)
      at      <- Gen.choose(1, raw.length - 1)
      control <- Gen.choose('\u0000', '\u001f')
    } yield raw.patch(at, control.toString, 0)

    forall(spoiled)(raw => expect.eql(PlayerName.from(raw), None))
  }

  pureTest("nothing, or nothing but space, is not a name") {
    expect.eql(PlayerName.from(""), None) and expect.eql(PlayerName.from("   "), None)
  }

  pureTest("the limit counts characters a person sees, not UTF-16 units") {
    val face = "😀"

    expect(PlayerName.from(face * PlayerName.MaxLength).isDefined) and
      expect.eql(PlayerName.from(face * (PlayerName.MaxLength + 1)), None)
  }
}
