package co.topl.bridge.managers

import cats.effect.kernel.Sync

import java.util.UUID
import java.util.concurrent.ConcurrentMap
import co.topl.bridge.PeginSessionState
import cats.effect.std.Queue

import cats.implicits._

sealed trait SessionEvent

case class SessionCreated(sessionId: String, sessionInfo: SessionInfo)
    extends SessionEvent
case class SessionUpdated(sessionId: String, sessionInfo: SessionInfo)
    extends SessionEvent

sealed trait SessionInfo

/** This class is used to store the session information for a pegin.
  *
  * @param currentWalletIdx
  *   The index of the wallet that is currently being used.
  * @param mintTemplateName
  *   The name under which the mint template is stored.
  * @param redeemAddress
  *   The address where the pegin will be redeemed.
  * @param escrowAddress
  *   The address where the BTC to peg in will be deposited.
  * @param scriptAsm
  *   The script that is used to redeem the pegin.
  * @param toplBridgePKey
  *   The public key of the bridge.
  * @param sha256
  *   The hash of the secret that is used to redeem the pegin.
  * @param mintingBTCState
  *   The state of the minting process for this session.
  */
case class PeginSessionInfo(
    currentWalletIdx: Int,
    mintTemplateName: String,
    redeemAddress: String,
    escrowAddress: String,
    scriptAsm: String,
    toplBridgePKey: String,
    sha256: String,
    mintingBTCState: PeginSessionState
) extends SessionInfo

case class PegoutSessionInfo(
    bridgeFellowshipId: String,
    address: String
) extends SessionInfo

trait SessionManagerAlgebra[F[_]] {
  def createNewSession(
      sessionInfo: SessionInfo
  ): F[String]

  def getSession(
      sessionId: String
  ): F[SessionInfo]

  def updateSession(
      sessionId: String,
      sessionInfo: SessionInfo
  ): F[Unit]
}

object SessionManagerImpl {
  def make[F[_]: Sync](
      queue: Queue[F, SessionEvent],
      map: ConcurrentMap[String, SessionInfo]
  ): SessionManagerAlgebra[F] = new SessionManagerAlgebra[F] {
    def createNewSession(
        sessionInfo: SessionInfo
    ): F[String] = {
      for {
        sessionId <- Sync[F].delay(UUID.randomUUID().toString)
        _ <- Sync[F].delay(map.put(sessionId, sessionInfo))
        _ <- queue.offer(SessionCreated(sessionId, sessionInfo))
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

    def updateSession(
        sessionId: String,
        sessionInfo: SessionInfo
    ): F[Unit] = {
      Sync[F].delay(map.put(sessionId, sessionInfo)) >>
        queue.offer(SessionUpdated(sessionId, sessionInfo))
    }

  }
}
