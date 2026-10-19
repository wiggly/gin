package wiggly.gin.ui

import org.scalajs.dom

import scala.util.Try

/** The name a player last typed. `localStorage` rather than the `sessionStorage` a seat lives in,
  * because a name outlives a game and a tab.
  */
object Profile {

  val key: String = "wiggly-gin:name"

  // `localStorage` throws when the browser blocks storage, and `recall` runs at start-up.
  def remember(name: String): Unit = {
    val _ = Try(dom.window.localStorage.setItem(key, name))
  }

  def recall: Option[String] = Try(dom.window.localStorage.getItem(key)).toOption.flatMap(Option(_))
}
