package co.topl.bridge.managers

import cats.effect.kernel.Sync

import java.util.UUID
import java.util.concurrent.ConcurrentMap

sealed trait SessionInfo

/** This class is used to store the session information for a pegin.
  *
  * @param currentWalletIdx
  *   The index of the wallet that is currently being used.
  * @param scriptAsm
  *   The script that is used to redeem the pegin.
  */
case class PeginSessionInfo(
    currentWalletIdx: Int,
    scriptAsm: String
) extends SessionInfo

case class PegoutSessionInfo(
    bridgeFellowshipId: String,
    quivrScript: String
) extends SessionInfo

trait SessionManagerAlgebra[F[_]] {
  def createNewSession(
      sessionInfo: SessionInfo
  ): F[String]

  def getSession(
      sessionId: String
  ): F[SessionInfo]
}

object PeginSessionManagerImpl {
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
        {
          Option(map.get(sessionId))
        },
        new IllegalArgumentException("Invalid session ID")
      )
    }

  }
}
