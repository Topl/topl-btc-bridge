package co.topl.bridge.consensus.modules

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
import co.topl.bridge.consensus.Fellowship
import co.topl.bridge.consensus.Lvl
import co.topl.bridge.consensus.SystemGlobalState
import co.topl.bridge.consensus.Template
import co.topl.bridge.consensus.ToplBTCBridgeConsensusParamConfig
import co.topl.bridge.consensus.managers.BTCWalletAlgebra
import co.topl.bridge.consensus.managers.SessionEvent
import co.topl.bridge.consensus.managers.SessionManagerImpl
import co.topl.bridge.consensus.managers.WalletManagementUtils
import co.topl.bridge.consensus.statemachine.pegin.PeginStateMachine
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.server.Router
import org.http4s.server.staticcontent.resourceServiceBuilder
import org.typelevel.log4cats.SelfAwareStructuredLogger
import co.topl.brambl.models.SeriesId
import co.topl.brambl.models.GroupId

import java.util.concurrent.ConcurrentHashMap
import co.topl.bridge.consensus.BTCWaitExpirationTime
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.BTCConfirmationThreshold
import co.topl.bridge.consensus.ToplConfirmationThreshold
import co.topl.bridge.consensus.BTCRetryThreshold
import co.topl.bridge.consensus.ClientId
import co.topl.bridge.consensus.PublicApiClientGrpc
import java.security.PublicKey

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
      params: ToplBTCBridgeConsensusParamConfig,
      publicApiClientGrpcMap: Map[
        ClientId,
        (PublicApiClientGrpc[IO], PublicKey)
      ],
      queue: Queue[IO, SessionEvent],
      walletManager: BTCWalletAlgebra[IO],
      pegInWalletManager: BTCWalletAlgebra[IO],
      logger: SelfAwareStructuredLogger[IO],
      currentBitcoinNetworkHeight: Ref[IO, Int],
      currentToplHeight: Ref[IO, Long],
      currentState: Ref[IO, SystemGlobalState]
  )(implicit
      fromFellowship: Fellowship,
      fromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      btcRetryThreshold: BTCRetryThreshold,
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId
  ) = {
    val staticAssetsService = resourceServiceBuilder[IO]("/static").toRoutes
    val walletKeyApi = WalletKeyApi.make[IO]()
    implicit val walletApi = WalletApi.make[IO](walletKeyApi)
    val walletRes = walletResource(params.toplWalletDb)
    implicit val walletStateAlgebra = WalletStateApi
      .make[IO](walletRes, walletApi)
    implicit val transactionBuilderApi = TransactionBuilderApi.make[IO](
      params.toplNetwork.networkId,
      NetworkConstants.MAIN_LEDGER_ID
    )
    implicit val genusQueryAlgebra = GenusQueryAlgebra.make[IO](
      channelResource(
        params.toplHost,
        params.toplPort,
        params.toplSecureConnection
      )
    )
    implicit val fellowshipStorageApi = FellowshipStorageApi.make(walletRes)
    implicit val templateStorageApi = TemplateStorageApi.make(walletRes)
    implicit val sessionManager =
      SessionManagerImpl.make[IO](queue, new ConcurrentHashMap())
    val walletManagementUtils = new WalletManagementUtils(
      walletApi,
      walletKeyApi
    )
    import co.topl.brambl.syntax._
    implicit val defaultMintingFee = Lvl(params.mintingFee)
    implicit val asyncForIO = IO.asyncForIO
    implicit val l = logger
    implicit val btcWaitExpirationTime = new BTCWaitExpirationTime(
      params.btcWaitExpirationTime
    )
    implicit val toplWaitExpirationTime = new ToplWaitExpirationTime(
      params.toplWaitExpirationTime
    )
    implicit val btcConfirmationThreshold = new BTCConfirmationThreshold(
      params.btcConfirmationThreshold
    )
    implicit val toplConfirmationThreshold = new ToplConfirmationThreshold(
      params.toplConfirmationThreshold
    )
    for {
      keyPair <- walletManagementUtils.loadKeys(
        params.toplWalletSeedFile,
        params.toplWalletPassword
      )

    } yield {
      implicit val kp = keyPair
      implicit val defaultFeePerByte = params.feePerByte
      implicit val iPeginWalletManager = pegInWalletManager
      implicit val toplChannelResource = channelResource(
        params.toplHost,
        params.toplPort,
        params.toplSecureConnection
      )
      val peginStateMachine = PeginStateMachine
        .make[IO](
          currentBitcoinNetworkHeight,
          currentToplHeight,
          new ConcurrentHashMap()
        )
      (
        grpcServices(
          publicApiClientGrpcMap,
          keyPair,
          sessionManager,
          pegInWalletManager,
          walletManager,
          params.btcNetwork,
          currentToplHeight
        ),
        InitializationModule
          .make[IO](currentBitcoinNetworkHeight, currentState),
        peginStateMachine
      )
    }
  }
}
