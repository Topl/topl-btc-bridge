package co.topl.tbcli.view

import cats.Show

case class OutputInitSession(
    pkey: String,
    sha256: String
)

object OutputView {

  implicit val showInitSession: Show[OutputInitSession] = Show.show { a =>
    import io.circe.syntax._
    import io.circe.generic.auto._
    a.asJson.spaces2
  }

}
