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

class StartSessionControllerSpec extends CatsEffectSuite {

  val testKey =
    "0295bb5a3b80eeccb1e38ab2cbac2545e9af6c7012cdc8d53bd276754c54fc2e4a"

  val testHash =
    "497a39b618484855ebb5a2cabf6ee52ff092e7c17f8bfe79313529f9774f83a2"

  test("StartSessionController should start a session") {
    assertIOBoolean(
      for {
        km0 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          "topl-btc-bridge/src/test/resources/wallet.json",
          "password"
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
          RegTest
        )
        sessionInfo <- sessionManager.getSession(res.sessionID)
      } yield (sessionInfo.secretHash == testHash) &&
        (sessionInfo.userPKey == testKey) &&
        (sessionInfo.bridgePKey == currentPubKey.hex)
    )
  }
}
