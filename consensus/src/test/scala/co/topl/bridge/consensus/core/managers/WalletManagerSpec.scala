package co.topl.bridge.consensus.core.managers

import cats.effect.IO
import co.topl.bridge.consensus.core.RegTest
import munit.CatsEffectSuite
import co.topl.bridge.consensus.core.utils.KeyGenerationUtils

class WalletManagerSpec extends CatsEffectSuite {

  test("BTCWalletAlgebra should generate a new key and increment the index") {
    assertIOBoolean(
      for {
        km <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          "src/test/resources/wallet.json",
          "password"
        )
        sut <- co.topl.bridge.consensus.core.managers.BTCWalletImpl.make[IO](km)
        res <- sut.getCurrentPubKeyAndPrepareNext()
        (idx, pubKey) = res
        res <- sut.getCurrentPubKeyAndPrepareNext()
        (idx2, pubKey2) = res
      } yield (idx == 0) && (idx2 == 1) && (pubKey != pubKey2)
    )
  }
  test("BTCWalletAlgebra should get new key without incrementing the index") {
    assertIOBoolean(
      for {
        km <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          "src/test/resources/wallet.json",
          "password"
        )
        sut <- co.topl.bridge.consensus.core.managers.BTCWalletImpl.make[IO](km)
        res <- sut.getCurrentPubKeyAndPrepareNext()
        (idx, _) = res
        pubKey <- sut.getCurrentPubKey()
        pubKey2 <- sut.getCurrentPubKey()
      } yield (pubKey == pubKey2)
    )
  }
}
