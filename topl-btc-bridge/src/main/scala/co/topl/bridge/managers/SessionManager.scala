package co.topl.bridge.managers

import cats.effect.kernel.Sync

import java.util.UUID
import java.util.concurrent.ConcurrentMap

/**
  * This class is used to store the session information for a pegin.
  *
  * @param currentWalletIdx The index of the wallet that is currently being used.
  * @param scriptAsm The script that is used to redeem the pegin.
  */
case class PeginSessionInfo(
    currentWalletIdx: Int,
    scriptAsm: String,
)

case class PegoutSessionInfo(
    bridgePKey: String,
    currentWalletIdx: Int,
    userPKey: String,
    secretHash: String,
    quivrScript: String,
    address: String
)

trait SessionManagerAlgebra[F[_]] {
  def createNewSession(
      sessionInfo: PeginSessionInfo
  ): F[String]

  def getSession(
      sessionId: String
  ): F[PeginSessionInfo]
}

object PeginSessionManagerImpl {
  def make[F[_]: Sync](
      map: ConcurrentMap[String, PeginSessionInfo]
  ): SessionManagerAlgebra[F] = new SessionManagerAlgebra[F] {
    def createNewSession(
        sessionInfo: PeginSessionInfo
    ): F[String] = {
      import cats.implicits._
      for {
        sessionId <- Sync[F].delay(UUID.randomUUID().toString)
        _ <- Sync[F].delay(map.put(sessionId, sessionInfo))
      } yield sessionId
    }

    def getSession(
        sessionId: String
    ): F[PeginSessionInfo] = {
      Sync[F].fromOption(
        {
          Option(map.get(sessionId))
        },
        new IllegalArgumentException("Invalid session ID")
      )
    }

  }
}
