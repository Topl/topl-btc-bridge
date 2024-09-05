package co.topl.bridge.consensus.modules

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Queue
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.constants.NetworkConstants
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.brambl.servicekit.FellowshipStorageApi
import co.topl.brambl.servicekit.TemplateStorageApi
import co.topl.brambl.servicekit.WalletKeyApi
import co.topl.brambl.servicekit.WalletStateApi
import co.topl.brambl.servicekit.WalletStateResource
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.BTCRetryThreshold
import co.topl.bridge.consensus.CurrentView
import co.topl.bridge.consensus.Fellowship
import co.topl.bridge.consensus.PublicApiClientGrpcMap
import co.topl.bridge.consensus.SystemGlobalState
import co.topl.bridge.consensus.Template
import co.topl.bridge.consensus.ToplBTCBridgeConsensusParamConfig
import co.topl.bridge.consensus.managers.BTCWalletAlgebra
import co.topl.bridge.consensus.managers.SessionEvent
import co.topl.bridge.consensus.managers.SessionManagerImpl
import co.topl.bridge.consensus.managers.WalletManagementUtils
import co.topl.bridge.consensus.monitor.MonitorStateMachine
import co.topl.bridge.consensus.pbft.PBFTState
import co.topl.bridge.consensus.persistence.StorageApi
import co.topl.bridge.consensus.service.StateMachineReply.Result
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.consensus.PBFTProtocolClientGrpc
import co.topl.shared.ClientId
import co.topl.shared.ConsensusClientGrpc
import co.topl.shared.ReplicaCount
import io.grpc.Metadata
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.security.{KeyPair => JKeyPair}
import java.util.concurrent.ConcurrentHashMap
import java.security.PublicKey
import co.topl.shared.ReplicaId
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.brambl.utils.Encoding

trait AppModule
    extends WalletStateResource
    with StateMachineServiceModule
    with PbftServiceModule {

  def webUI() = HttpRoutes.of[IO] { case request @ GET -> Root =>
    StaticFile
      .fromResource("/static/index.html", Some(request))
      .getOrElseF(InternalServerError())
  }

  def createApp(
      replicaKeysMap: Map[Int, PublicKey],
      replicaKeyPair: JKeyPair,
      pbftProtocolClient: PBFTProtocolClientGrpc[IO],
      idReplicaClientMap: Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
      params: ToplBTCBridgeConsensusParamConfig,
      queue: Queue[IO, SessionEvent],
      walletManager: BTCWalletAlgebra[IO],
      pegInWalletManager: BTCWalletAlgebra[IO],
      logger: Logger[IO],
      currentBitcoinNetworkHeight: Ref[IO, Int],
      currentSequenceRef: Ref[IO, Long],
      currentToplHeight: Ref[IO, Long],
      currentState: Ref[IO, SystemGlobalState]
  )(implicit
      publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
      currentView: CurrentView[IO],
      clientId: ClientId,
      storageApi: StorageApi[IO],
      consensusClient: ConsensusClientGrpc[IO],
      replicaId: ReplicaId,
      replicaCount: ReplicaCount,
      fromFellowship: Fellowship,
      fromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      btcRetryThreshold: BTCRetryThreshold,
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId
  ) = {
    import co.topl.bridge.consensus._
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
    implicit val sessionManagerPermanent =
      SessionManagerImpl.makePermanent[IO](storageApi, queue)
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
      stableCheckpoint <- Ref.of(StableCheckpoint(0, Map(), Map()))
      unstableCheckpoints <- Ref.of[
        IO,
        Map[(Long, String), Map[Int, CheckpointRequest]]
      ](Map())
      stateSnapshot <- Ref.of[IO, (Long, String, Map[String, PBFTState])](
        (0, Encoding.encodeToHex(Array.emptyByteArray), Map())
      )
    } yield {
      implicit val lastReplyMap = new LastReplyMap(
        new ConcurrentHashMap[(ClientId, Long), Result]()
      )
      implicit val kp = new ToplKeypair(keyPair)
      implicit val defaultFeePerByte = params.feePerByte
      implicit val iPeginWalletManager = new PeginWalletManager(
        pegInWalletManager
      )
      implicit val iBridgeWalletManager = new BridgeWalletManager(walletManager)
      implicit val btcNetwork = params.btcNetwork
      implicit val toplChannelResource = channelResource(
        params.toplHost,
        params.toplPort,
        params.toplSecureConnection
      )
      implicit val currentBTCHeightRef =
        new CurrentBTCHeight[IO](currentBitcoinNetworkHeight)
      implicit val currentToplHeightRef = new CurrentToplHeight[IO](
        currentToplHeight
      )
      implicit val sessionState = new SessionState(
        new ConcurrentHashMap[String, PBFTState]()
      )
      implicit val checkpointInterval = new CheckpointInterval(
        params.checkpointInterval
      )
      implicit val lastStableCheckpointRef: StableCheckpointRef[IO] =
        new StableCheckpointRef[IO](stableCheckpoint)
      implicit val untableCheckpoints: UnstableCheckpointsRef[IO] =
        new UnstableCheckpointsRef[IO](
          unstableCheckpoints
        )
      implicit val stateSnapshotRef = new StateSnapshotRef[IO](
        stateSnapshot
      )
      implicit val watermarkRef = new WatermarkRef[IO](
        Ref.unsafe[IO, (Long, Long)]((0, 0))
      )
      implicit val kWatermark = new KWatermark(params.kWatermark)
      val peginStateMachine = MonitorStateMachine
        .make[IO](
          currentBitcoinNetworkHeight,
          currentToplHeight,
          new ConcurrentHashMap()
        )
      (
        stateMachineService(
          replicaKeyPair,
          pbftProtocolClient,
          idReplicaClientMap,
          currentSequenceRef
        ),
        InitializationModule
          .make[IO](currentBitcoinNetworkHeight, currentState),
        peginStateMachine,
        pbftService(
          pbftProtocolClient,
          replicaKeyPair,
          replicaKeysMap
        )
      )
    }
  }
}
