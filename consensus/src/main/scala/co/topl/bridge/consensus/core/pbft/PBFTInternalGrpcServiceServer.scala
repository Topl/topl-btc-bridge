package co.topl.bridge.consensus.core.pbft

import cats.data.Validated
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.implicits._
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.brambl.utils.Encoding
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.core.BitcoinNetworkIdentifiers
import co.topl.bridge.consensus.core.BridgeWalletManager
import co.topl.bridge.consensus.core.CheckpointInterval
import co.topl.bridge.consensus.core.CurrentBTCHeightRef
import co.topl.bridge.consensus.core.CurrentToplHeightRef
import co.topl.bridge.consensus.core.CurrentViewRef
import co.topl.bridge.consensus.core.Fellowship
import co.topl.bridge.consensus.core.KWatermark
import co.topl.bridge.consensus.core.LastReplyMap
import co.topl.bridge.consensus.core.PeginWalletManager
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.SessionState
import co.topl.bridge.consensus.core.StableCheckpointRef
import co.topl.bridge.consensus.core.StateSnapshotRef
import co.topl.bridge.consensus.core.Template
import co.topl.bridge.consensus.core.ToplKeypair
import co.topl.bridge.consensus.core.UnstableCheckpointsRef
import co.topl.bridge.consensus.core.WatermarkRef
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PBFTInternalServiceFs2Grpc
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.shared.BTCWaitExpirationTime
import co.topl.bridge.consensus.shared.Lvl
import co.topl.bridge.consensus.shared.ToplWaitExpirationTime
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.consensus.subsystems.monitor.SessionManagerAlgebra
import co.topl.bridge.shared.BridgeCryptoUtils
import co.topl.bridge.shared.ClientId
import co.topl.bridge.shared.Empty
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaId
import co.topl.bridge.shared.implicits._
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.ServerServiceDefinition
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger

import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey

object PBFTInternalGrpcServiceServer {

  import co.topl.bridge.consensus.core.pbft.StateMachineExecution._

  def pbftInternalGrpcServiceServerAux[F[_]: Async: Logger](
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F],
      keyPair: KeyPair,
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      watermarkRef: WatermarkRef[F],
      kWatermark: KWatermark,
      latestStateSnapshotRef: StateSnapshotRef[F],
      lastStableCheckpointRef: StableCheckpointRef[F],
      unstableCheckpointsRef: UnstableCheckpointsRef[F],
      checkpointInterval: CheckpointInterval,
      storageApi: StorageApi[F],
      replica: ReplicaId,
      replicaCount: ReplicaCount,
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      currentViewRef: CurrentViewRef[F],
      sessionManager: SessionManagerAlgebra[F],
      toplKeypair: ToplKeypair,
      sessionState: SessionState,
      currentBTCHeightRef: CurrentBTCHeightRef[F],
      btcNetwork: BitcoinNetworkIdentifiers,
      pegInWalletManager: PeginWalletManager[F],
      bridgeWalletManager: BridgeWalletManager[F],
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
      templateStorageAlgebra: TemplateStorageAlgebra[F],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      tba: TransactionBuilderApi[F],
      currentToplHeight: CurrentToplHeightRef[F],
      walletApi: WalletApi[F],
      wsa: WalletStateAlgebra[F],
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId,
      utxoAlgebra: GenusQueryAlgebra[F],
      channelResource: Resource[F, ManagedChannel],
      defaultMintingFee: Lvl,
      lastReplyMap: LastReplyMap,
      defaultFromFellowship: Fellowship,
      defaultFromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      defaultFeePerByte: CurrencyUnit
  ) = new PBFTInternalServiceFs2Grpc[F, Metadata] {

    private def checkRequestSignatures(
        request: PrePrepareRequest
    ): F[Boolean] = {
      val publicKey = publicApiClientGrpcMap
        .underlying(new ClientId(request.payload.get.clientNumber))
        ._2
      BridgeCryptoUtils.verifyBytes[F](
        publicKey,
        request.payload.get.signableBytes,
        request.payload.get.signature.toByteArray()
      )
    }

    private def checkDigest(
        requestDigest: Array[Byte],
        payloadSignableBytes: Array[Byte]
    ): F[Boolean] = {
      val isValidDigest =
        Encoding.encodeToHex(requestDigest) == Encoding.encodeToHex(
          MessageDigest
            .getInstance("SHA-256")
            .digest(payloadSignableBytes)
        )
      isValidDigest.pure[F]
    }

    private def checkViewNumber(
        requestViewNumber: Long
    ): F[Boolean] = {
      for {
        currentView <- currentViewRef.underlying.get
        isValidViewNumber = requestViewNumber == currentView
      } yield isValidViewNumber
    }

    private def checkWaterMark()
        : F[Boolean] = // FIXME: add check when watermarks are implemented
      true.pure[F]

    override def prePrepare(
        request: PrePrepareRequest,
        ctx: Metadata
    ): F[Empty] = {
      import org.typelevel.log4cats.syntax._
      for {
        _ <- trace"Received pre-prepare request"
        reqSignCheck <- checkRequestSignatures(request).map(
          Validated.condNel(_, (), "Invalid request signature")
        )
        prePreSignCheck <- checkMessageSignaturePrimary(
          replicaKeysMap,
          request.signableBytes,
          request.signature.toByteArray()
        ).map(
          Validated.condNel(_, (), "Invalid pre-prepare message signature")
        )
        digestCheck <- checkDigest(
          request.digest.toByteArray(),
          request.payload.get.signableBytes
        ).map(
          Validated.condNel(_, (), "Invalid digest")
        )
        viewNumberCheck <- checkViewNumber(request.viewNumber).map(
          Validated.condNel(_, (), "Invalid view number")
        )
        waterMarkCheck <- checkWaterMark().map(
          Validated.condNel(_, (), "Invalid water mark")
        )
        canInsert <- storageApi
          .getPrePrepareMessage(request.viewNumber, request.sequenceNumber)
          .map(x =>
            Validated.condNel(
              x.map(y =>
                Encoding.encodeToHex(y.digest.toByteArray()) == Encoding
                  .encodeToHex(request.digest.toByteArray())
              ).getOrElse(true),
              (),
              "Failed to insert pre-prepare request"
            )
          )
        _ <- (
          reqSignCheck,
          prePreSignCheck,
          digestCheck,
          viewNumberCheck,
          waterMarkCheck,
          canInsert
        ).mapN((_, _, _, _, _, _) => ())
          .fold(
            errors =>
              error"Error handling pre-prepare request: ${errors.toList.mkString(", ")}",
            _ => storageApi.insertPrePrepareMessage(request)
          )
        prepareRequest = PrepareRequest(
          viewNumber = request.viewNumber,
          sequenceNumber = request.sequenceNumber,
          digest = request.digest,
          replicaId = replica.id
        )
        signedBytes <- BridgeCryptoUtils.signBytes[F](
          keyPair.getPrivate(),
          prepareRequest.signableBytes
        )
        prepareRequestSigned = prepareRequest.withSignature(
          ByteString.copyFrom(signedBytes)
        )
        _ <- pbftProtocolClientGrpc.prepare(
          prepareRequestSigned
        )
        _ <- Async[F].start(
          waitForProtocol(
            request.payload.get,
            keyPair,
            request.digest,
            request.viewNumber,
            pbftProtocolClientGrpc,
            request.sequenceNumber
          )
        )
      } yield Empty()
    }

    override def prepare(
        request: PrepareRequest,
        ctx: Metadata
    ): F[Empty] = {
      import org.typelevel.log4cats.syntax._
      for {
        reqSignCheck <- checkMessageSignature(
          request.replicaId,
          replicaKeysMap,
          request.signableBytes,
          request.signature.toByteArray()
        ).map(
          Validated.condNel(_, (), "Invalid request signature")
        )
        viewNumberCheck <- checkViewNumber(request.viewNumber).map(
          Validated.condNel(_, (), "Invalid view number")
        )
        waterMarkCheck <- checkWaterMark().map(
          Validated.condNel(_, (), "Invalid water mark")
        )
        _ <- (
          reqSignCheck,
          viewNumberCheck,
          waterMarkCheck
        ).mapN((_, _, _) => ())
          .fold(
            errors =>
              error"Error handling prepare request: ${errors.toList.mkString(", ")}",
            _ => storageApi.insertPrepareMessage(request)
          )
      } yield Empty()
    }

    override def checkpoint(
        request: CheckpointRequest,
        ctx: Metadata
    ): F[Empty] = {
      CheckpointActivity(
        replicaKeysMap,
        request
      )
    }

    override def commit(request: CommitRequest, ctx: Metadata): F[Empty] = {
      import org.typelevel.log4cats.syntax._
      for {
        reqSignCheck <- checkMessageSignature(
          request.replicaId,
          replicaKeysMap,
          request.signableBytes,
          request.signature.toByteArray()
        ).map(
          Validated.condNel(_, (), "Invalid request signature")
        )
        viewNumberCheck <- checkViewNumber(request.viewNumber).map(
          Validated.condNel(_, (), "Invalid view number")
        )
        waterMarkCheck <- checkWaterMark().map(
          Validated.condNel(_, (), "Invalid water mark")
        )
        _ <- (
          reqSignCheck,
          viewNumberCheck,
          waterMarkCheck
        ).mapN((_, _, _) => ())
          .fold(
            errors =>
              error"Error handling commit request: ${errors.toList.mkString(", ")}",
            _ => storageApi.insertCommitMessage(request)
          )
      } yield Empty()
    }

  }

  def pbftInternalGrpcServiceServer[F[_]: Async: Logger](
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F],
      keyPair: KeyPair,
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      watermarkRef: WatermarkRef[F],
      kWatermark: KWatermark,
      latestStateSnapshotRef: StateSnapshotRef[F],
      lastStableCheckpointRef: StableCheckpointRef[F],
      unstableCheckpointsRef: UnstableCheckpointsRef[F],
      checkpointInterval: CheckpointInterval,
      storageApi: StorageApi[F],
      replica: ReplicaId,
      replicaCount: ReplicaCount,
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      currentViewRef: CurrentViewRef[F],
      sessionManager: SessionManagerAlgebra[F],
      toplKeypair: ToplKeypair,
      sessionState: SessionState,
      currentBTCHeightRef: CurrentBTCHeightRef[F],
      btcNetwork: BitcoinNetworkIdentifiers,
      pegInWalletManager: PeginWalletManager[F],
      bridgeWalletManager: BridgeWalletManager[F],
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
      templateStorageAlgebra: TemplateStorageAlgebra[F],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      tba: TransactionBuilderApi[F],
      currentToplHeight: CurrentToplHeightRef[F],
      walletApi: WalletApi[F],
      wsa: WalletStateAlgebra[F],
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId,
      utxoAlgebra: GenusQueryAlgebra[F],
      channelResource: Resource[F, ManagedChannel],
      defaultMintingFee: Lvl,
      lastReplyMap: LastReplyMap,
      defaultFromFellowship: Fellowship,
      defaultFromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      defaultFeePerByte: CurrencyUnit
  ): Resource[F, ServerServiceDefinition] =
    PBFTInternalServiceFs2Grpc.bindServiceResource(
      pbftInternalGrpcServiceServerAux(
        pbftProtocolClientGrpc,
        keyPair,
        replicaKeysMap
      )
    )
}
