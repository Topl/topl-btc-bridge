package co.topl.bridge.services

import cats.effect.kernel.Resource
import cats.effect.IO
import scala.collection.mutable

object SessionManager {

  def createSessionMap(map: mutable.Map[String, SessionInfo]): Resource[IO, mutable.Map[String, SessionInfo]] = {
    Resource.make(
      IO(map)
    )(_ => IO.unit)
  }

}
