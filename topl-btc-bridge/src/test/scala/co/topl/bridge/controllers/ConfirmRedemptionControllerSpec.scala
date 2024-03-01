package co.topl.bridge.controllers

import cats.effect.IO
import co.topl.bridge.managers.BTCWalletImpl
import co.topl.bridge.managers.PeginSessionInfo
import co.topl.bridge.managers.PeginSessionManagerImpl
import co.topl.shared.RegTest
import co.topl.shared.StartPeginSessionRequest
import co.topl.shared.utils.KeyGenerationUtils
import munit.CatsEffectSuite

import java.util.concurrent.ConcurrentHashMap
import co.topl.shared.ConfirmRedemptionRequest
import co.topl.shared.SessionNotFoundError

class ConfirmRedemptionControllerSpec extends CatsEffectSuite with SharedData {

  test(
    "ConfirmRedemptionController should create the correct transaction"
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
        sessionManager = PeginSessionManagerImpl.make[IO](
          new ConcurrentHashMap[String, PeginSessionInfo]()
        )
        sessionInfo <- StartSessionController.startPeginSession(
          StartPeginSessionRequest(
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
    "ConfirmRedemptionController should return an error if the session does not exist"
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
        sessionManager = PeginSessionManagerImpl.make[IO](
          new ConcurrentHashMap[String, PeginSessionInfo]()
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
