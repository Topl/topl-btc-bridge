package co.topl.bridge.consensus.core.pbft

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.servicekit.WalletKeyApi
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.core.BitcoinNetworkIdentifiers
import co.topl.bridge.consensus.core.BridgeWalletManager
import co.topl.bridge.consensus.core.CurrentBTCHeightRef
import co.topl.bridge.consensus.core.CurrentToplHeightRef
import co.topl.bridge.consensus.core.CurrentViewRef
import co.topl.bridge.consensus.core.PeginWalletManager
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.RegTest
import co.topl.bridge.consensus.core.SessionState
import co.topl.bridge.consensus.core.StableCheckpoint
import co.topl.bridge.consensus.core.StableCheckpointRef
import co.topl.bridge.consensus.core.StateSnapshotRef
import co.topl.bridge.consensus.core.ToplKeypair
import co.topl.bridge.consensus.core.UnstableCheckpointsRef
import co.topl.bridge.consensus.core.WatermarkRef
import co.topl.bridge.consensus.core.managers.WalletManagementUtils
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.consensus.shared.utils.ConfUtils._
import co.topl.bridge.consensus.subsystems.monitor.SessionManagerAlgebra
import co.topl.bridge.shared.BridgeCryptoUtils
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import io.grpc.ManagedChannel
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger

import java.util.concurrent.ConcurrentHashMap

trait PBFTInternalGrpcServiceServerSpecAux extends SampleData {

  def createSimpleInternalServer(
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[IO]
  )(implicit
      storageApi: StorageApi[IO],
      pegInWalletManager: PeginWalletManager[IO],
      bridgeWalletManager: BridgeWalletManager[IO],
      tba: TransactionBuilderApi[IO],
      wsa: WalletStateAlgebra[IO],
      utxoAlgebra: GenusQueryAlgebra[IO],
      sessionManager: SessionManagerAlgebra[IO],
      channelResource: Resource[IO, ManagedChannel],
      bitcoindInstance: BitcoindRpcClient,
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[IO],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
      templateStorageAlgebra: TemplateStorageAlgebra[IO],
      logger: Logger[IO]
  ) = {
    val walletKeyApi = WalletKeyApi.make[IO]()
    implicit val walletApi = WalletApi.make[IO](walletKeyApi)
    val walletManagementUtils = new WalletManagementUtils(
      walletApi,
      walletKeyApi
    )
    for {
      replicaKeyPair <- BridgeCryptoUtils
        .getKeyPair[IO](privateKeyFile)
      replicaKeysMap <- createReplicaPublicKeyMap[IO](conf).toResource
      lowAndHigh <- Ref.of[IO, (Long, Long)]((0L, 0L)).toResource
      state <- Ref
        .of[IO, (Long, String, Map[String, PBFTState])]((0L, "", Map.empty))
        .toResource
      stableCheckpoint <- Ref
        .of[IO, StableCheckpoint](StableCheckpoint(0L, Map.empty, Map.empty))
        .toResource
      unstableCheckpoint <- Ref
        .of[
          IO,
          Map[
            (Long, String),
            Map[Int, CheckpointRequest]
          ]
        ](Map.empty)
        .toResource
      currentView <- Ref.of[IO, Long](0L).toResource
      keypair <- walletManagementUtils
        .loadKeys(
          toplWalletFile,
          testToplPassword
        )
        .toResource
      currentBTCHeight <- Ref.of[IO, Int](0).toResource
      currentToplHeight <- Ref.of[IO, Long](0L).toResource
    } yield {
      implicit val watermarkRef = new WatermarkRef(lowAndHigh)
      implicit val stateSnapshotRef = StateSnapshotRef[IO](state)
      implicit val stableCheckpointRef =
        StableCheckpointRef[IO](stableCheckpoint)
      implicit val unstableCheckpointsRef = UnstableCheckpointsRef[IO](
        unstableCheckpoint
      )
      implicit val currentViewRef = new CurrentViewRef(currentView)
      implicit val toplKeypair = new ToplKeypair(keypair)
      implicit val sessionState = new SessionState(new ConcurrentHashMap())
      implicit val currentBTCHeightRef = new CurrentBTCHeightRef[IO](
        currentBTCHeight
      )
      implicit val bitcoinNetworkIdentifier: BitcoinNetworkIdentifiers =
        RegTest
      implicit val currentToplHeightRef = new CurrentToplHeightRef(
        currentToplHeight
      )
      PBFTInternalGrpcServiceServer.pbftInternalGrpcServiceServerAux(
        pbftProtocolClientGrpc,
        replicaKeyPair,
        replicaKeysMap
      )
    }
  }
}
