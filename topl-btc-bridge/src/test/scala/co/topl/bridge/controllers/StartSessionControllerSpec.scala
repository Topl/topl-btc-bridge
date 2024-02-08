package co.topl.bridge.controllers

import munit.CatsEffectSuite
import co.topl.shared.utils.KeyGenerationUtils
import co.topl.shared.RegTest
import co.topl.bridge.managers.BTCWalletImpl
import cats.effect.IO
import co.topl.bridge.managers.SessionManagerImpl
import java.util.concurrent.ConcurrentHashMap
import co.topl.bridge.managers.SessionInfo
import co.topl.shared.StartSessionRequest
import co.topl.shared.InvalidKey
import co.topl.shared.InvalidHash

class StartSessionControllerSpec extends CatsEffectSuite with SharedData {

  test("StartSessionController should start a session") {
    assertIOBoolean(
      for {
        km0 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          peginWalletFile,
          testPassword
        )
        peginWallet <- BTCWalletImpl.make[IO](km0)
        currentPubKey <- peginWallet.getCurrentPubKey()
        sessionManager = SessionManagerImpl.make[IO](
          new ConcurrentHashMap[String, SessionInfo]()
        )
        res <- StartSessionController.startSession(
          StartSessionRequest(
            testKey,
            testHash
          ),
          peginWallet,
          sessionManager,
          testBlockToRecover,
          RegTest
        )
        sessionInfo <- sessionManager.getSession(res.toOption.get.sessionID)
      } yield (sessionInfo.secretHash == testHash) &&
        (sessionInfo.userPKey == testKey) &&
        (sessionInfo.bridgePKey == currentPubKey.hex)
    )
  }

  test("StartSessionController should fai with invalid key") {
    assertIOBoolean(
      for {
        km0 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          peginWalletFile,
          testPassword
        )
        peginWallet <- BTCWalletImpl.make[IO](km0)
        currentPubKey <- peginWallet.getCurrentPubKey()
        sessionManager = SessionManagerImpl.make[IO](
          new ConcurrentHashMap[String, SessionInfo]()
        )
        res <- StartSessionController.startSession(
          StartSessionRequest(
            "invalidKey",
            testHash
          ),
          peginWallet,
          sessionManager,
          testBlockToRecover,
          RegTest
        )
      } yield res.isLeft && res.swap.toOption.get == InvalidKey(
        "Invalid key invalidKey"
      )
    )
  }

  test("StartSessionController should fai with invalid hash") {
    assertIOBoolean(
      for {
        km0 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          peginWalletFile,
          testPassword
        )
        peginWallet <- BTCWalletImpl.make[IO](km0)
        currentPubKey <- peginWallet.getCurrentPubKey()
        sessionManager = SessionManagerImpl.make[IO](
          new ConcurrentHashMap[String, SessionInfo]()
        )
        res <- StartSessionController.startSession(
          StartSessionRequest(
            testKey,
            "invalidHash"
          ),
          peginWallet,
          sessionManager,
          testBlockToRecover,
          RegTest
        )
      } yield res.isLeft && res.swap.toOption.get == InvalidHash(
        "Invalid hash invalidHash"
      )
    )
  }
}
