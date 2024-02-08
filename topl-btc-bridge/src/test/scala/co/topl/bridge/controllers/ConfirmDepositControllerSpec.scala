package co.topl.bridge.controllers

import co.topl.brambl.utils.Encoding
import co.topl.bridge.stubs.BaseToplWalletAlgebra
import co.topl.bridge.stubs.BaseTransactionAlgebra
import co.topl.shared.ConfirmDepositRequest
import munit.CatsEffectSuite
import cats.effect.IO

class ConfirmDepositControllerSpec extends CatsEffectSuite with SharedData {

  import co.topl.bridge.stubs.UnitTestStubs._

  test("ConfirmDepositController should fail on invalid tx id for group UTXO") {
    import ConfirmDepositController._
    assertIOBoolean(
      for {
        keyPair <- walletManagementUtils.loadKeys(
          toplWalletFile,
          testToplPassword
        )
        res <- confirmDeposit(
          keyPair,
          testToplNetworkId.networkId,
          ConfirmDepositRequest(
            "sessionID",
            "invalidBase58",
            transactionOutputAddress02.index,
            Encoding.encodeToBase58(transactionId01.value.toByteArray()),
            transactionOutputAddress03.index,
            1000L
          ),
          new BaseToplWalletAlgebra(),
          new BaseTransactionAlgebra[IO](),
          10L
        )
      } yield res.isLeft
    )
  }

  test(
    "ConfirmDepositController should fail on invalid tx id for series UTXO"
  ) {
    import ConfirmDepositController._
    assertIOBoolean(
      for {
        keyPair <- walletManagementUtils.loadKeys(
          toplWalletFile,
          testToplPassword
        )
        res <- confirmDeposit(
          keyPair,
          testToplNetworkId.networkId,
          ConfirmDepositRequest(
            "sessionID",
            Encoding.encodeToBase58(transactionId01.value.toByteArray()),
            transactionOutputAddress02.index,
            "invalidBase58",
            transactionOutputAddress03.index,
            1000L
          ),
          new BaseToplWalletAlgebra(),
          new BaseTransactionAlgebra[IO](),
          10L
        )
      } yield res.isLeft
    )
  }

  test(
    "ConfirmDepositController should succeed on valid input"
  ) {
    import ConfirmDepositController._
    assertIOBoolean(
      for {
        keyPair <- walletManagementUtils.loadKeys(
          toplWalletFile,
          testToplPassword
        )
        res <- confirmDeposit(
          keyPair,
          testToplNetworkId.networkId,
          ConfirmDepositRequest(
            "sessionID",
            Encoding.encodeToBase58(transactionId01.value.toByteArray()),
            transactionOutputAddress02.index,
            Encoding.encodeToBase58(transactionId01.value.toByteArray()),
            transactionOutputAddress03.index,
            1000L
          ),
          new BaseToplWalletAlgebra(),
          new BaseTransactionAlgebra[IO](),
          10L
        )
      } yield res.isRight
    )
  }

}
