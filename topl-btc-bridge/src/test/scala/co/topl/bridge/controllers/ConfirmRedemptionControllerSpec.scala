package co.topl.bridge.controllers

import cats.effect.IO
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.constants.NetworkConstants
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.RpcChannelResource
import co.topl.brambl.servicekit.FellowshipStorageApi
import co.topl.brambl.servicekit.TemplateStorageApi
import co.topl.brambl.servicekit.WalletKeyApi
import co.topl.brambl.servicekit.WalletStateApi
import co.topl.brambl.servicekit.WalletStateResource
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.managers.BTCWalletImpl
import co.topl.bridge.managers.SessionInfo
import co.topl.bridge.managers.SessionManagerImpl
import co.topl.bridge.managers.ToplWalletImpl
import co.topl.bridge.managers.WalletManagementUtils
import co.topl.shared.ConfirmRedemptionRequest
import co.topl.shared.RegTest
import co.topl.shared.SessionNotFoundError
import co.topl.shared.StartPeginSessionRequest
import co.topl.shared.ToplPrivatenet
import co.topl.shared.utils.KeyGenerationUtils
import munit.CatsEffectSuite

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import cats.effect.std.Queue
import co.topl.bridge.managers.SessionEvent

class ConfirmRedemptionControllerSpec
    extends CatsEffectSuite
    with SharedData
    with WalletStateResource
    with RpcChannelResource {

  val tmpDirectory = FunFixture[Path](
    setup = { _ =>
      try {
        Files.delete(Paths.get(toplWalletDb))
      } catch {
        case _: Throwable => ()
      }
      val initialWalletDb = Paths.get(toplWalletDbInitial)
      Files.copy(initialWalletDb, Paths.get(toplWalletDb))
    },
    teardown = { _ =>
      Files.delete(Paths.get(toplWalletDb))
    }
  )

  tmpDirectory.test(
    "ConfirmRedemptionController should create the correct transaction"
  ) { _ =>
    val walletKeyApi = WalletKeyApi.make[IO]()
    val walletApi = WalletApi.make[IO](walletKeyApi)
    val walletManagementUtils = new WalletManagementUtils(
      walletApi,
      walletKeyApi
    )
    val walletStateAlgebra = WalletStateApi
      .make[IO](walletResource(toplWalletDb), walletApi)
    val transactionBuilderApi = TransactionBuilderApi.make[IO](
      ToplPrivatenet.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )
    val genusQueryAlgebra = GenusQueryAlgebra.make[IO](
      channelResource(
        "localhost",
        9084,
        false
      )
    )
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
        queue <- Queue.unbounded[IO, SessionEvent]
        sessionManager = SessionManagerImpl.make[IO](
          queue,
          new ConcurrentHashMap[String, SessionInfo]()
        )
        keypair <- walletManagementUtils.loadKeys(
          toplWalletFile,
          testToplPassword
        )
        toplWalletImpl = ToplWalletImpl.make[IO](
          IO.asyncForIO,
          walletApi,
          FellowshipStorageApi.make(walletResource(toplWalletDb)),
          TemplateStorageApi.make(walletResource(toplWalletDb)),
          walletStateAlgebra,
          transactionBuilderApi,
          genusQueryAlgebra
        )
        sessionInfo <- StartSessionController.startPeginSession(
          StartPeginSessionRequest(
            testKey,
            testHash
          ),
          peginWallet,
          sessionManager,
          testBlockToRecover,
          keypair,
          toplWalletImpl,
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
  tmpDirectory.test(
    "ConfirmRedemptionController should return an error if the session does not exist"
  ) { _ =>
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
        queue <- Queue.unbounded[IO, SessionEvent]
        sessionManager = SessionManagerImpl.make[IO](
          queue,
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
