package wiggly.gin.ui

import tyrian.Html.*
import tyrian.{Aria, Html, SVG}
import wiggly.gin.core.domain.{Card, Rank, Suit}

/** Cards as pictures.
  *
  * A face carries its index in two opposite corners, as a printed card does, so that it reads the
  * same from either side of a fan. The middle is one large suit rather than a pip layout: the
  * index carries the meaning, and the rest is drawing code nobody needs to read.
  */
object Cards {

  def face[M](card: Card): Html[M] = {
    svg(
      SVG.viewBox    := "0 0 70 100",
      cls            := s"card-art face ${colour(card.suit)}",
      role           := "img",
      Aria.ariaLabel := name(card)
    )(
      outline,
      index(card),
      SVG.g(attribute("transform", "rotate(180 35 50)"))(index(card)),
      SVG.textTag(SVG.x := "35", SVG.y := "62", cls := "pip")(text(symbol(card.suit)))
    )
  }

  /** The pattern a back fills with is defined once, in the page, rather than once for every back
    * on the table, because an id has to be unique in a document.
    */
  def back[M]: Html[M] = {
    svg(
      SVG.viewBox    := "0 0 70 100",
      cls            := "card-art back",
      role           := "img",
      Aria.ariaLabel := "a face-down card"
    )(
      outline,
      SVG.rect(
        SVG.x := "6",
        SVG.y := "6",
        attribute("width", "58"),
        attribute("height", "88"),
        SVG.rx := "3",
        cls    := "weave"
      )
    )
  }

  def name(card: Card): String =
    s"${card.rank.toString.toLowerCase} of ${card.suit.toString.toLowerCase}"

  def short(card: Card): String = s"${rankIndex(card.rank)}${symbol(card.suit)}"

  private def outline[M]: Html[M] = {
    SVG.rect(
      SVG.x := "1",
      SVG.y := "1",
      attribute("width", "68"),
      attribute("height", "98"),
      SVG.rx := "6",
      cls    := "outline"
    )
  }

  private def index[M](card: Card): Html[M] = {
    SVG.g(cls := "index")(
      SVG.textTag(SVG.x := "9", SVG.y := "16")(text(rankIndex(card.rank))),
      SVG.textTag(SVG.x := "9", SVG.y := "28")(text(symbol(card.suit)))
    )
  }

  private def rankIndex(rank: Rank): String = {
    rank match {
      case Rank.Ace   => "A"
      case Rank.Jack  => "J"
      case Rank.Queen => "Q"
      case Rank.King  => "K"
      case other      => other.order.toString
    }
  }

  private def symbol(suit: Suit): String = {
    suit match {
      case Suit.Spades   => "♠"
      case Suit.Hearts   => "♥"
      case Suit.Diamonds => "♦"
      case Suit.Clubs    => "♣"
    }
  }

  private def colour(suit: Suit): String = {
    suit match {
      case Suit.Hearts | Suit.Diamonds => "red"
      case Suit.Clubs | Suit.Spades    => "black"
    }
  }
}
