package com.kubukoz.next

import scala.scalajs.LinkingInfo

import org.scalajs.dom
import scala.scalajs.js.annotation.JSExportTopLevel
import slinky.core.FunctionalComponent
import slinky.web.html.div
import slinky.web.ReactDOM
import slinky.core.facade.Hooks
import slinky.web.html.button
import slinky.web.html.onClick

object Front {

  @JSExportTopLevel("main")
  def main(): Unit = {
    if (LinkingInfo.developmentMode) {
      slinky.hot.initialize()
    }

    val container = Option(dom.document.getElementById("root")).getOrElse {
      val elem = dom.document.createElement("div")
      elem.id = "root"
      dom.document.body.appendChild(elem)
      elem
    }

    val _ = ReactDOM.render(App.component(()), container)
  }

}

object App {

  val component: FunctionalComponent[Unit] = FunctionalComponent { _ =>
    val (n, setN) = Hooks.useState(42)

    div(button("stonks", onClick := { () => setN(_ + 1) }), s"$n")
  }

}
