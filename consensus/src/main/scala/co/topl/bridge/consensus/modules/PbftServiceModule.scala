package co.topl.bridge.consensus.modules

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
import co.topl.bridge.consensus.BTCWaitExpirationTime
import co.topl.bridge.consensus.BitcoinNetworkIdentifiers
import co.topl.bridge.consensus.BridgeWalletManager
import co.topl.bridge.consensus.CurrentBTCHeight
import co.topl.bridge.consensus.CurrentToplHeight
import co.topl.bridge.consensus.CurrentView
import co.topl.bridge.consensus.Fellowship
import co.topl.bridge.consensus.LastReplyMap
import co.topl.bridge.consensus.Lvl
import co.topl.bridge.consensus.PeginWalletManager
import co.topl.bridge.consensus.PublicApiClientGrpcMap
import co.topl.bridge.consensus.SessionState
import co.topl.bridge.consensus.Template
import co.topl.bridge.consensus.ToplKeypair
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.managers.SessionManagerAlgebra
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PBFTInternalServiceFs2Grpc
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.persistence.StorageApi
import co.topl.bridge.shared.Empty
import co.topl.consensus.PBFTProtocolClientGrpc
import co.topl.shared.BridgeCryptoUtils
import co.topl.shared.ClientId
import co.topl.shared.ReplicaCount
import co.topl.shared.implicits._
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
import co.topl.shared.ReplicaId
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.CheckpointInterval

trait PbftServiceModule {

  import co.topl.bridge.consensus.pbft.StateMachineExecution._

  def pbftService[F[_]: Async: Logger](
      pbftProtocolClientGrpc: PBFTProtocolClientGrpc[F],
      keyPair: KeyPair,
      replicaKeysMap: Map[Int, PublicKey]
  )(implicit
      checkpointInterval: CheckpointInterval,
      storageApi: StorageApi[F],
      replica: ReplicaId,
      replicaCount: ReplicaCount,
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      currentViewRef: CurrentView[F],
      sessionManager: SessionManagerAlgebra[F],
      toplKeypair: ToplKeypair,
      sessionState: SessionState,
      currentBTCHeightRef: CurrentBTCHeight[F],
      btcNetwork: BitcoinNetworkIdentifiers,
      pegInWalletManager: PeginWalletManager[F],
      bridgeWalletManager: BridgeWalletManager[F],
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
      templateStorageAlgebra: TemplateStorageAlgebra[F],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      tba: TransactionBuilderApi[F],
      currentToplHeight: CurrentToplHeight[F],
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
      new PBFTInternalServiceFs2Grpc[F, Metadata] {

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

        private def checkMessageSignature(
            requestSignableBytes: Array[Byte],
            requestSignature: Array[Byte]
        ): F[Boolean] = {
          for {
            currentView <- currentViewRef.underlying.get
            currentPrimary = (currentView % replicaCount.value).toInt
            publicKey = replicaKeysMap(currentPrimary)
            isValidSignature <- BridgeCryptoUtils.verifyBytes[F](
              publicKey,
              requestSignableBytes,
              requestSignature
            )
          } yield isValidSignature
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
            prePreSignCheck <- checkMessageSignature(
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
            // _ <- storageApi.insertPrepareMessage(prepareRequestSigned)
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
            checkpointRequest: CheckpointRequest,
            ctx: Metadata
        ): F[Empty] = ???

        override def commit(request: CommitRequest, ctx: Metadata): F[Empty] = {
          import org.typelevel.log4cats.syntax._
          for {
            reqSignCheck <- checkMessageSignature(
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
    )
}
