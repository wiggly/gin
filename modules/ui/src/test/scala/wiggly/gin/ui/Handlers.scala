package wiggly.gin.ui

import org.scalajs.dom
import tyrian.{Event, Html, Tag}

/** What a rendered page would send, read without a browser.
  *
  * A handler that ignores its event can be run here with a bare one. `dragstart` reads the event's
  * transfer, which Node does not have, so that handler is counted rather than run.
  */
object Handlers {

  def sent(html: Html[Msg], name: String): List[Msg] =
    named(html, name).map(_.msg.asInstanceOf[dom.Event => Msg](new dom.Event(name)))

  def count(html: Html[Msg], name: String): Int = named(html, name).size

  /** The events on each element, one list per element, so that a test can pair them. */
  def byElement(html: Html[?]): List[List[Event[?, ?]]] = {
    html match {
      case Tag(_, attributes, children, _) =>
        attributes.collect { case event: Event[?, ?] => event } :: children.flatMap {
          case child: Html[?] => byElement(child)
          case _              => Nil
        }
      case _ => Nil
    }
  }

  private def named(html: Html[?], name: String): List[Event[?, ?]] = {
    html match {
      case Tag(_, attributes, children, _) =>
        attributes.collect { case event: Event[?, ?] if event.name == name => event } ++
          children.flatMap {
            case child: Html[?] => named(child, name)
            case _              => Nil
          }
      case _ => Nil
    }
  }
}
