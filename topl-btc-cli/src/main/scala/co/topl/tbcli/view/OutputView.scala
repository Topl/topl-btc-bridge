package co.topl.tbcli.view

import cats.Show
import co.topl.bridge.shared.StartPeginSessionRequest

object OutputView {

  implicit val showInitSession: Show[StartPeginSessionRequest] = Show.show { a =>
    import io.circe.syntax._
    import io.circe.generic.auto._
    a.asJson.spaces2
  }

}
