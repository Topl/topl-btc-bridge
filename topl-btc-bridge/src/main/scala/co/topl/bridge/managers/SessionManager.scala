package co.topl.bridge.managers

import cats.effect.kernel.Sync

import java.util.UUID
import java.util.concurrent.ConcurrentMap
import co.topl.bridge.PeginSessionState
import cats.effect.std.Queue

import cats.implicits._
import co.topl.bridge.utils.MiscUtils

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
      sessionInfo: SessionInfo
  ): F[String]

  def getSession(
      sessionId: String
  ): F[Option[SessionInfo]]

  def updateSession(
      sessionId: String,
      sessionInfoTransformer: PeginSessionInfo => SessionInfo
  ): F[Unit]

  def removeSession(
      sessionId: String
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
    ): F[Option[SessionInfo]] = {
      Sync[F].delay(
        Option(map.get(sessionId))
      )
    }

    def updateSession(
        sessionId: String,
        sessionInfoTransformer: PeginSessionInfo => SessionInfo
    ): F[Unit] = {
      for {
        sessionInfo <- Sync[F].delay(map.get(sessionId))
        someNewSessionInfo = MiscUtils.sessionInfoPeginPrism
          .getOption(sessionInfo)
          .map(sessionInfoTransformer)
        _ <- someNewSessionInfo
          .map(x =>
            Sync[F].delay(map.replace(sessionId, x)) >> queue.offer(
              SessionUpdated(sessionId, x)
            )
          )
          .getOrElse(Sync[F].unit)
      } yield ()
    }

    def removeSession(sessionId: String): F[Unit] =
      Sync[F].delay(map.remove(sessionId))

  }
}
