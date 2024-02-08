package co.topl.tbcli.view

import cats.Show
import co.topl.shared.StartSessionRequest

object OutputView {

  implicit val showInitSession: Show[StartSessionRequest] = Show.show { a =>
    import io.circe.syntax._
    import io.circe.generic.auto._
    a.asJson.spaces2
  }

}
