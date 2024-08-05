package co.topl.bridge.consensus.controllers

import cats.effect.IO
import cats.effect.kernel.Ref
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.constants.NetworkConstants
import co.topl.brambl.dataApi.RpcChannelResource
import co.topl.brambl.servicekit.FellowshipStorageApi
import co.topl.brambl.servicekit.TemplateStorageApi
import co.topl.brambl.servicekit.WalletKeyApi
import co.topl.brambl.servicekit.WalletStateApi
import co.topl.brambl.servicekit.WalletStateResource
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.RegTest
import co.topl.bridge.consensus.ToplPrivatenet
import co.topl.bridge.consensus.managers.BTCWalletImpl
import co.topl.bridge.consensus.managers.WalletManagementUtils
import co.topl.bridge.consensus.service.StartSessionOperation
import co.topl.bridge.consensus.utils.KeyGenerationUtils
import co.topl.shared.InvalidHash
import co.topl.shared.InvalidKey
import munit.CatsEffectSuite

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class StartSessionControllerSpec
    extends CatsEffectSuite
    with WalletStateResource
    with RpcChannelResource
    with SharedData {

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

  tmpDirectory.test("StartSessionController should start a pegin session") {
    _ =>
      val walletKeyApi = WalletKeyApi.make[IO]()
      implicit val walletApi = WalletApi.make[IO](walletKeyApi)
      val walletManagementUtils = new WalletManagementUtils(
        walletApi,
        walletKeyApi
      )
      implicit val walletStateAlgebra = WalletStateApi
        .make[IO](walletResource(toplWalletDb), walletApi)
      implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
        ToplPrivatenet.networkId,
        NetworkConstants.MAIN_LEDGER_ID
      )

      implicit val fellowshipStorageApi =
        FellowshipStorageApi.make(walletResource(toplWalletDb))
      implicit val templateStorageApi =
        TemplateStorageApi.make(walletResource(toplWalletDb))
      assertIOBoolean(
        for {
          km0 <- KeyGenerationUtils.createKeyManager[IO](
            RegTest,
            peginWalletFile,
            testPassword
          )
          peginWallet <- BTCWalletImpl.make[IO](km0)
          keyPair <- walletManagementUtils.loadKeys(
            toplWalletFile,
            testToplPassword
          )
          currentToplHeight <- Ref[IO].of(1L)
          res <- StartSessionController.startPeginSession(
            "pegin",
            StartSessionOperation(
              None,
              testKey,
              testHash
            ),
            peginWallet,
            peginWallet,
            keyPair,
            currentToplHeight,
            RegTest
          )
        } yield (res.toOption.get._1.btcPeginCurrentWalletIdx == 0)
      )
  }

  tmpDirectory.test(
    "StartSessionController should fai with invalid key (pegin)"
  ) { _ =>
    val walletKeyApi = WalletKeyApi.make[IO]()
    implicit val walletApi = WalletApi.make[IO](walletKeyApi)
    val walletManagementUtils = new WalletManagementUtils(
      walletApi,
      walletKeyApi
    )
    implicit val walletStateAlgebra = WalletStateApi
      .make[IO](walletResource(toplWalletDb), walletApi)
    implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
      ToplPrivatenet.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )

    implicit val fellowshipStorageApi =
      FellowshipStorageApi.make(walletResource(toplWalletDb))
    implicit val templateStorageApi =
      TemplateStorageApi.make(walletResource(toplWalletDb))
    assertIOBoolean(
      for {
        keypair <- walletManagementUtils.loadKeys(
          toplWalletFile,
          testToplPassword
        )
        km0 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          peginWalletFile,
          testPassword
        )
        peginWallet <- BTCWalletImpl.make[IO](km0)
        currentToplHeight <- Ref[IO].of(1L)
        res <- StartSessionController.startPeginSession(
          "pegin",
          StartSessionOperation(
            None,
            "invalidKey",
            testHash
          ),
          peginWallet,
          peginWallet,
          keypair,
          currentToplHeight,
          RegTest
        )
      } yield res.isLeft && res.swap.toOption.get == InvalidKey(
        "Invalid key invalidKey"
      )
    )
  }

  test("StartSessionController should fai with invalid hash") {
    val walletKeyApi = WalletKeyApi.make[IO]()
    implicit val walletApi = WalletApi.make[IO](walletKeyApi)
    val walletManagementUtils = new WalletManagementUtils(
      walletApi,
      walletKeyApi
    )
    implicit val walletStateAlgebra = WalletStateApi
      .make[IO](walletResource(toplWalletDb), walletApi)
    implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
      ToplPrivatenet.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )

    implicit val fellowshipStorageApi =
      FellowshipStorageApi.make(walletResource(toplWalletDb))
    implicit val templateStorageApi =
      TemplateStorageApi.make(walletResource(toplWalletDb))
    assertIOBoolean(
      for {
        keypair <- walletManagementUtils.loadKeys(
          toplWalletFile,
          testToplPassword
        )
        km0 <- KeyGenerationUtils.createKeyManager[IO](
          RegTest,
          peginWalletFile,
          testPassword
        )
        peginWallet <- BTCWalletImpl.make[IO](km0)
        currentToplHeight <- Ref[IO].of(1L)
        res <- StartSessionController.startPeginSession(
          "pegin",
          StartSessionOperation(
            None,
            testKey,
            "invalidHash"
          ),
          peginWallet,
          peginWallet,
          keypair,
          currentToplHeight,
          RegTest
        )
      } yield res.isLeft && res.swap.toOption.get == InvalidHash(
        "Invalid hash invalidHash"
      )
    )
  }

}
