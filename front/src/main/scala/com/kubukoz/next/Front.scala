package com.kubukoz.next

import scala.scalajs.LinkingInfo
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.util.Random
import cats.data.NonEmptyList
import org.scalajs.dom
import slinky.core.FunctionalComponent
import slinky.core.facade.Hooks
import slinky.web.ReactDOM
import slinky.web.html.div
import slinky.web.html.key
import slinky.web.html.onClick
import cats.effect.*
import cats.implicits.*
import java.util.concurrent.atomic.AtomicReference
import cats.effect.unsafe.implicits.*

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

  import scala.concurrent.duration.*

  val component: FunctionalComponent[Unit] = FunctionalComponent { _ =>
    //using a core model
    val (state, setState) = Hooks.useState(Model(NonEmptyList.of(('a', 0))))

    val handleClicked = SyncIO {
      setState(m =>
        m.copy(
          value = m
            .value
            .append(
              Random.nextPrintableChar() -> (m.value.last._2 + 1)
            )
            .toList
            .takeRight(30)
            .toNel
            .get
        )
      )
    }

    //just a POC of using cats-effect here
    Hooks.useEffect { () =>
      val theIO = IO(handleClicked.unsafeRunSync()).delayBy(16.millis)

      // this could be a little nicer huh
      val tok = new AtomicReference[IO[Unit]](IO.unit)
      theIO.start.unsafeRunAsync {
        _.traverse(fiber => IO(tok.set(fiber.cancel))).unsafeRunAndForget()
      }

      () => IO(tok.get).flatten.unsafeRunAndForget()
    }

    import slinky.styledcomponents.*

    case class StonksProps(color: String)

    val stonks = styled.button(css"""
      display: block;
      background-color: ${(_: StonksProps).color}
    """)

    val buttonz = state
      .value
      .map { case (value, index) =>
        stonks(StonksProps("red"))(
          s"stonks $value",
          onClick := (handleClicked.unsafeRunSync _),
          key := index.toString
        )
      }

    div(buttonz.toList)
  }

}
