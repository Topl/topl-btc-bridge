package co.topl.bridge.consensus.subsystems.monitor

import cats.effect.kernel.Sync
import cats.effect.std.Queue
import cats.implicits._
import co.topl.bridge.consensus.core.PeginSessionState
import co.topl.bridge.consensus.core.persistence.StorageApi
import co.topl.bridge.consensus.core.utils.MiscUtils

sealed trait SessionEvent

case class SessionCreated(sessionId: String, sessionInfo: SessionInfo)
    extends SessionEvent
case class SessionUpdated(sessionId: String, sessionInfo: SessionInfo)
    extends SessionEvent

sealed trait SessionInfo

/** This class is used to store the session information for a pegin.
  *
  * @param btcPeginCurrentWalletIdx
  *   The index of the pegin wallet that is currently being used.
  * @param btcBridgeCurrentWalletIdx
  *   The index of the bridge wallet that is currently being used.
  * @param mintTemplateName
  *   The name under which the mint template is stored.
  * @param redeemAddress
  *   The address where the pegin will be redeemed.
  * @param escrowAddress
  *   The address where the BTC to peg in will be deposited.
  * @param scriptAsm
  *   The script that is used to redeem the pegin.
  * @param sha256
  *   The hash of the secret that is used to redeem the pegin.
  * @param mintingBTCState
  *   The state of the minting process for this session.
  */
case class PeginSessionInfo(
    btcPeginCurrentWalletIdx: Int,
    btcBridgeCurrentWalletIdx: Int,
    mintTemplateName: String,
    redeemAddress: String,
    escrowAddress: String,
    scriptAsm: String,
    sha256: String,
    minHeight: Long,
    maxHeight: Long,
    claimAddress: String,
    mintingBTCState: PeginSessionState
) extends SessionInfo

case class PegoutSessionInfo(
    bridgeFellowshipId: String,
    address: String
) extends SessionInfo

trait SessionManagerAlgebra[F[_]] {
  def createNewSession(
      sessionId: String,
      sessionInfo: SessionInfo
  ): F[String]

  def getSession(
      sessionId: String
  ): F[Option[SessionInfo]]

  def updateSession(
      sessionId: String,
      sessionInfoTransformer: PeginSessionInfo => SessionInfo
  ): F[Option[SessionInfo]]

  def removeSession(
      sessionId: String,
      finalState: PeginSessionState
  ): F[Unit]
}

object SessionManagerImpl {
  def makePermanent[F[_]: Sync](
      storageApi: StorageApi[F],
      queue: Queue[F, SessionEvent]
  ): SessionManagerAlgebra[F] = new SessionManagerAlgebra[F] {

    override def removeSession(
        sessionId: String,
        finalState: PeginSessionState
    ): F[Unit] = {
      updateSession(sessionId, _.copy(mintingBTCState = finalState)).void
    }

    def createNewSession(
        sessionId: String,
        sessionInfo: SessionInfo
    ): F[String] = {
      for {
        _ <- storageApi.insertNewSession(sessionId, sessionInfo)
        _ <- queue.offer(SessionCreated(sessionId, sessionInfo))
      } yield sessionId
    }

    def getSession(
        sessionId: String
    ): F[Option[SessionInfo]] = {
      storageApi.getSession(sessionId)
    }

    def updateSession(
        sessionId: String,
        sessionInfoTransformer: PeginSessionInfo => SessionInfo
    ): F[Option[SessionInfo]] = {
      for {
        someSessionInfo <- storageApi.getSession(sessionId)
        someNewSessionInfo = someSessionInfo.flatMap(sessionInfo =>
          MiscUtils.sessionInfoPeginPrism
            .getOption(sessionInfo)
            .map(sessionInfoTransformer)
        )
        _ <- someNewSessionInfo
          .map { x =>
            storageApi.updateSession(sessionId, x) >> queue
              .offer(
                SessionUpdated(sessionId, x)
              )
          }
          .getOrElse(Sync[F].unit)
      } yield someSessionInfo
    }

  }
}
