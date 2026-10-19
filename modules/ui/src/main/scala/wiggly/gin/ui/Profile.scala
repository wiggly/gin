package wiggly.gin.ui

import org.scalajs.dom

/** The name a player last typed. `localStorage` rather than the `sessionStorage` a seat lives in,
  * because a name outlives a game and a tab.
  */
object Profile {

  val key: String = "wiggly-gin:name"

  def remember(name: String): Unit = dom.window.localStorage.setItem(key, name)

  def recall: Option[String] = Option(dom.window.localStorage.getItem(key))
}
