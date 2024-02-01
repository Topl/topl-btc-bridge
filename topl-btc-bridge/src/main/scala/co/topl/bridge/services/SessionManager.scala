package co.topl.bridge.services

import cats.effect.kernel.Sync

import java.util.UUID
import java.util.concurrent.ConcurrentMap

trait SessionManagerAlgebra[F[_]] {
  def createNewSession(
      sessionInfo: SessionInfo
  ): F[String]

  def getSession(
      sessionId: String
  ): F[SessionInfo]
}

object SessionManagerImpl {
  def make[F[_]: Sync](
      map: ConcurrentMap[String, SessionInfo]
  ): SessionManagerAlgebra[F] = new SessionManagerAlgebra[F] {
    def createNewSession(
        sessionInfo: SessionInfo
    ): F[String] = {
      import cats.implicits._
      for {
        sessionId <- Sync[F].delay(UUID.randomUUID().toString)
        _ <- Sync[F].delay(map.put(sessionId, sessionInfo))
      } yield sessionId
    }

    def getSession(
        sessionId: String
    ): F[SessionInfo] = {
      Sync[F].fromOption(
        Option(map.get(sessionId)),
        new IllegalArgumentException("Invalid session ID")
      )
    }

  }
}
