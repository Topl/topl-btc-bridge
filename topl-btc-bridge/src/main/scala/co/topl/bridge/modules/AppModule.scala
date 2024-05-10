package co.topl.bridge.modules

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
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
import co.topl.bridge.SystemGlobalState
import co.topl.bridge.ToplBTCBridgeParamConfig
import co.topl.bridge.managers.BTCWalletAlgebra
import co.topl.bridge.statemachine.pegin.PeginStateMachine
import co.topl.bridge.managers.SessionEvent
import co.topl.bridge.managers.SessionManagerImpl
import co.topl.bridge.managers.ToplWalletImpl
import co.topl.bridge.managers.TransactionAlgebra
import co.topl.bridge.statemachine.pegin.WaitingBTCOps
import co.topl.bridge.managers.WalletManagementUtils
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.Router
import org.http4s.server.staticcontent.resourceServiceBuilder
import org.typelevel.log4cats.SelfAwareStructuredLogger

import java.util.concurrent.ConcurrentHashMap
import co.topl.bridge.statemachine.pegin.WaitingForRedemptionOps
import org.bitcoins.rpc.client.common.BitcoindRpcClient

trait AppModule
    extends WalletStateResource
    with RpcChannelResource
    with ApiServicesModule {

  def webUI() = HttpRoutes.of[IO] { case request @ GET -> Root =>
    StaticFile
      .fromResource("/static/index.html", Some(request))
      .getOrElseF(InternalServerError())
  }

  def createApp(
      params: ToplBTCBridgeParamConfig,
      bitcoindInstance: BitcoindRpcClient,
      fromFellowship: String,
      fromTemplate: String,
      queue: Queue[IO, SessionEvent],
      pegInWalletManager: BTCWalletAlgebra[IO],
      walletManager: BTCWalletAlgebra[IO],
      logger: SelfAwareStructuredLogger[IO],
      currentState: Ref[IO, SystemGlobalState]
  ) = {
    val staticAssetsService = resourceServiceBuilder[IO]("/static").toRoutes
    val walletKeyApi = WalletKeyApi.make[IO]()
    val walletApi = WalletApi.make[IO](walletKeyApi)
    val walletRes = walletResource(params.toplWalletDb)
    val walletStateAlgebra = WalletStateApi
      .make[IO](walletRes, walletApi)
    val transactionBuilderApi = TransactionBuilderApi.make[IO](
      params.toplNetwork.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )
    val genusQueryAlgebra = GenusQueryAlgebra.make[IO](
      channelResource(
        params.toplHost,
        params.toplPort,
        params.toplSecureConnection
      )
    )
    val toplWalletImpl = ToplWalletImpl.make[IO](
      IO.asyncForIO,
      walletApi,
      FellowshipStorageApi.make(walletRes),
      TemplateStorageApi.make(walletRes),
      walletStateAlgebra,
      transactionBuilderApi,
      genusQueryAlgebra
    )
    val transactionAlgebra = TransactionAlgebra.make[IO](
      walletApi,
      walletStateAlgebra,
      channelResource(
        params.toplHost,
        params.toplPort,
        params.toplSecureConnection
      )
    )
    val sessionManager =
      SessionManagerImpl.make[IO](queue, new ConcurrentHashMap())
    val walletManagementUtils = new WalletManagementUtils(
      walletApi,
      walletKeyApi
    )
    val waitingBTCForBlock = new WaitingBTCOps(
      walletStateAlgebra,
      transactionBuilderApi
    )(IO.asyncForIO)
    val waitingForRedemptionOps = new WaitingForRedemptionOps(
      bitcoindInstance,
      pegInWalletManager,
      walletManager
    )(IO.asyncForIO)
    for {
      keyPair <- walletManagementUtils.loadKeys(
        params.toplWalletSeedFile,
        params.toplWalletPassword
      )
      peginStateMachine = new PeginStateMachine(
        sessionManager,
        waitingBTCForBlock,
        waitingForRedemptionOps,
        keyPair,
        fromFellowship,
        fromTemplate,
        params.mintingFee,
        toplWalletImpl,
        transactionAlgebra,
        genusQueryAlgebra,
        params.feePerByte,
        new ConcurrentHashMap()
      )(IO.asyncForIO, logger)
      notFoundResponse <- NotFound(
        """<!DOCTYPE html>
          |<html>
          |<body>
          |<h1>Not found</h1>
          |<p>The page you are looking for is not found.</p>
          |<p>This message was generated on the server.</p>
          |</body>
          |</html>""".stripMargin('|'),
        headers.`Content-Type`(MediaType.text.html)
      )
      router = Router.define(
        "/" -> webUI(),
        "/api" -> apiServices(
          walletApi,
          walletStateAlgebra,
          genusQueryAlgebra,
          keyPair,
          sessionManager,
          pegInWalletManager,
          toplWalletImpl,
          params.blockToRecover,
          params.btcNetwork,
          params.toplNetwork,
          currentState
        )
      )(default = staticAssetsService)

    } yield (
      Kleisli[IO, Request[IO], Response[IO]] { request =>
        router.run(request).getOrElse(notFoundResponse)
      },
      new InitializationModule(
        transactionBuilderApi,
        walletStateAlgebra,
        walletApi,
        keyPair,
        genusQueryAlgebra,
        transactionAlgebra,
        currentState
      )(IO.asyncForIO, logger),
      peginStateMachine
    )
  }
}
