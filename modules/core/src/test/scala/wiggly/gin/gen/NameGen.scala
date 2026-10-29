package wiggly.gin.gen

import org.scalacheck.Gen
import wiggly.gin.core.domain.PlayerName

object NameGen {

  /** A name for a test that does not care which. Letters and digits only, so that a name never
    * collides with the punctuation a rendered page or a payload is searched for.
    */
  val name: Gen[PlayerName] = for {
    length <- Gen.choose(1, PlayerName.MaxLength)
    chars  <- Gen.listOfN(length, Gen.alphaNumChar)
  } yield named(chars.mkString)

  def named(raw: String): PlayerName =
    PlayerName.from(raw).getOrElse(sys.error(s"not a name: '$raw'"))
}
