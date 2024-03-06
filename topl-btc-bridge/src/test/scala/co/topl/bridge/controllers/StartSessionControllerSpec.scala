package co.topl.bridge.controllers

import munit.CatsEffectSuite
import co.topl.shared.utils.KeyGenerationUtils
import co.topl.shared.RegTest
import co.topl.bridge.managers.BTCWalletImpl
import cats.effect.IO
import co.topl.bridge.managers.SessionManagerImpl
import java.util.concurrent.ConcurrentHashMap
import co.topl.bridge.managers.PeginSessionInfo
import co.topl.shared.StartPeginSessionRequest
import co.topl.shared.InvalidKey
import co.topl.shared.InvalidHash
import co.topl.bridge.managers.SessionInfo
import co.topl.shared.ToplPrivatenet
import co.topl.shared.StartPegoutSessionRequest
import co.topl.brambl.wallet.WalletApi
import co.topl.brambl.servicekit.WalletKeyApi
import co.topl.bridge.managers.WalletManagementUtils
import co.topl.bridge.managers.ToplWalletImpl
import co.topl.brambl.servicekit.FellowshipStorageApi
import co.topl.brambl.servicekit.TemplateStorageApi
import co.topl.brambl.servicekit.WalletStateResource
import co.topl.brambl.servicekit.WalletStateApi
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.constants.NetworkConstants
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.RpcChannelResource
import co.topl.bridge.managers.PegoutSessionInfo
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import co.topl.shared.InvalidInput

class StartSessionControllerSpec
    extends CatsEffectSuite
    with WalletStateResource
    with RpcChannelResource
    with SharedData {

  val tmpDirectory = FunFixture[Path](
    setup = { _ =>
      val initialWalletDb = Paths.get(toplWalletDbInitial)
      Files.copy(initialWalletDb, Paths.get(toplWalletDb))
    },
    teardown = { f => Files.delete(f) }
  )

  test("StartSessionController should start a pegin session") {
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
        res <- StartSessionController.startPeginSession(
          StartPeginSessionRequest(
            testKey,
            testHash
          ),
          peginWallet,
          sessionManager,
          testBlockToRecover,
          RegTest
        )
        sessionInfo <- sessionManager.getSession(res.toOption.get.sessionID)
      } yield (sessionInfo.asInstanceOf[PeginSessionInfo].currentWalletIdx == 0)
    )
  }

  tmpDirectory.test("StartSessionController should start a pegout session") {
    _ =>
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
      val sessionManager = SessionManagerImpl.make[IO](
        new ConcurrentHashMap[String, SessionInfo]()
      )
      assertIOBoolean(
        for {
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
          res <- StartSessionController.startPegoutSession[IO](
            StartPegoutSessionRequest(
              pegoutTestKey,
              1000,
              testHash
            ),
            ToplPrivatenet,
            keypair, // keypair
            toplWalletImpl, // toplWalletAlgebra
            sessionManager, // session manager
            1000
          )
          sessionInfo <- sessionManager.getSession(res.toOption.get.sessionID)
        } yield (sessionInfo
          .asInstanceOf[PegoutSessionInfo]
          .address == "ptetP7jshHVPgNWRFrYBAMCrnfAwpRn6hSNuAcMfgukVtA1x3wkjCPqqwD7w")
      )
  }

  tmpDirectory.test(
    "StartSessionController should fail with invalid key (pegout)"
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
    val sessionManager = SessionManagerImpl.make[IO](
      new ConcurrentHashMap[String, SessionInfo]()
    )
    assertIOBoolean(
      for {
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
        res <- StartSessionController.startPegoutSession[IO](
          StartPegoutSessionRequest(
            "invalidKey",
            1000,
            testHash
          ),
          ToplPrivatenet,
          keypair, // keypair
          toplWalletImpl, // toplWalletAlgebra
          sessionManager, // session manager
          1000
        )
      } yield res.isLeft && res.swap.toOption.get == InvalidKey(
        "Invalid key invalidKey"
      )
    )
  }

  test("StartSessionController should fai with invalid key (pegin)") {
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
        res <- StartSessionController.startPeginSession(
          StartPeginSessionRequest(
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
        res <- StartSessionController.startPeginSession(
          StartPeginSessionRequest(
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

  tmpDirectory.test(
    "StartSessionController should fail with invalid hash (pegout)"
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
    val sessionManager = SessionManagerImpl.make[IO](
      new ConcurrentHashMap[String, SessionInfo]()
    )
    assertIOBoolean(
      for {
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
        res <- StartSessionController.startPegoutSession[IO](
          StartPegoutSessionRequest(
            testKey,
            1000,
            "invalidHash"
          ),
          ToplPrivatenet,
          keypair, // keypair
          toplWalletImpl, // toplWalletAlgebra
          sessionManager, // session manager
          1000
        )
      } yield res.isLeft && res.swap.toOption.get == InvalidHash(
        "Invalid hash invalidHash"
      )
    )
  }

  tmpDirectory.test(
    "StartSessionController should fail with invalid height (pegout)"
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
    val sessionManager = SessionManagerImpl.make[IO](
      new ConcurrentHashMap[String, SessionInfo]()
    )
    assertIOBoolean(
      for {
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
        res <- StartSessionController.startPegoutSession[IO](
          StartPegoutSessionRequest(
            pegoutTestKey,
            -1,
            testHash
          ),
          ToplPrivatenet,
          keypair, // keypair
          toplWalletImpl, // toplWalletAlgebra
          sessionManager, // session manager
          1000
        )
      } yield res.isLeft && res.swap.toOption.get == InvalidInput(
        "Invalid block height -1"
      )
    )
  }

}
