package co.topl.bridge.controllers

import cats.effect.IO
import co.topl.bridge.managers.BTCWalletImpl
import co.topl.bridge.managers.SessionInfo
import co.topl.bridge.managers.SessionManagerImpl
import co.topl.shared.RegTest
import co.topl.shared.StartSessionRequest
import co.topl.shared.utils.KeyGenerationUtils
import munit.CatsEffectSuite

import java.util.concurrent.ConcurrentHashMap
import co.topl.shared.ConfirmRedemptionRequest
import co.topl.shared.SessionNotFoundError

class ConfirmRedemptionControllerSpec extends CatsEffectSuite with SharedData {

  test(
    "ConfirmRedemptionControllerSpec should create the correct transaction"
  ) {
    assertIOBoolean(
      for {
        km0 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          peginWalletFile,
          testPassword
        )
        peginWallet <- BTCWalletImpl.make[IO](km0)
        km1 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          walletFile,
          testPassword
        )
        wallet <- BTCWalletImpl.make[IO](km1)
        currentPubKey <- peginWallet.getCurrentPubKey()
        sessionManager = SessionManagerImpl.make[IO](
          new ConcurrentHashMap[String, SessionInfo]()
        )
        sessionInfo <- StartSessionController.startSession(
          StartSessionRequest(
            testKey,
            testHash
          ),
          peginWallet,
          sessionManager,
          testBlockToRecover,
          RegTest
        )
        res <- ConfirmRedemptionController.confirmRedemption(
          ConfirmRedemptionRequest(
            sessionInfo.toOption.get.sessionID,
            testInputTx,
            0,
            2,
            490000,
            testSecret
          ),
          peginWallet,
          wallet,
          sessionManager
        )
      } yield res.isRight && res.toOption.get.tx.startsWith(testTx)
    )
  }
  test(
    "ConfirmRedemptionControllerSpec should return an error if the session does not exist"
  ) {
    assertIOBoolean(
      for {
        km0 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          peginWalletFile,
          testPassword
        )
        peginWallet <- BTCWalletImpl.make[IO](km0)
        km1 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          walletFile,
          testPassword
        )
        wallet <- BTCWalletImpl.make[IO](km1)
        currentPubKey <- peginWallet.getCurrentPubKey()
        sessionManager = SessionManagerImpl.make[IO](
          new ConcurrentHashMap[String, SessionInfo]()
        )
        res <- ConfirmRedemptionController.confirmRedemption(
          ConfirmRedemptionRequest(
            "fakeSession",
            testInputTx,
            0,
            2,
            490000,
            testSecret
          ),
          peginWallet,
          wallet,
          sessionManager
        )
      } yield res.isLeft && res.swap.toOption.get
        .isInstanceOf[SessionNotFoundError]
    )
  }
}
