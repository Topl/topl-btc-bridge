package co.topl.bridge.consensus.core.modules

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
import co.topl.bridge.consensus.shared.BTCWaitExpirationTime
import co.topl.bridge.consensus.core.BitcoinNetworkIdentifiers
import co.topl.bridge.consensus.core.BridgeWalletManager
import co.topl.bridge.consensus.core.CheckpointInterval
import co.topl.bridge.consensus.core.CurrentBTCHeight
import co.topl.bridge.consensus.core.CurrentToplHeight
import co.topl.bridge.consensus.core.CurrentView
import co.topl.bridge.consensus.core.Fellowship
import co.topl.bridge.consensus.core.LastReplyMap
import co.topl.bridge.consensus.shared.Lvl
import co.topl.bridge.consensus.core.PeginWalletManager
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.SessionState
import co.topl.bridge.consensus.core.StableCheckpointRef
import co.topl.bridge.consensus.core.Template
import co.topl.bridge.consensus.core.ToplKeypair
import co.topl.bridge.consensus.shared.ToplWaitExpirationTime
import co.topl.bridge.consensus.core.UnstableCheckpointsRef
import co.topl.bridge.consensus.core.managers.SessionManagerAlgebra
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PBFTInternalServiceFs2Grpc
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.core.persistence.StorageApi
import co.topl.bridge.shared.Empty
import co.topl.consensus.PBFTProtocolClientGrpc
import co.topl.bridge.shared.BridgeCryptoUtils
import co.topl.bridge.shared.ClientId
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaId
import co.topl.bridge.shared.implicits._
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
import co.topl.bridge.consensus.core.StateSnapshotRef
import co.topl.bridge.consensus.core.pbft.PBFTState
import co.topl.bridge.consensus.core.WatermarkRef
import co.topl.bridge.consensus.core.KWatermark


trait PbftServiceModule {

  import co.topl.bridge.consensus.core.pbft.StateMachineExecution._

  def pbftService[F[_]: Async: Logger](
      pbftProtocolClientGrpc: PBFTProtocolClientGrpc[F],
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
            request: CheckpointRequest,
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
            lastStableCheckpoint <- lastStableCheckpointRef.underlying.get
            waterMarkCheck <- Validated
              .condNel(
                request.sequenceNumber < lastStableCheckpoint.sequenceNumber,
                (),
                "Checkpoint message "
              )
              .pure[F]
            isInLogCheck <- storageApi
              .getCheckpointMessage(request.sequenceNumber, request.replicaId)
              .map(
                _.map(x =>
                  Validated.condNel(
                    Encoding.encodeToHex(x.digest.toByteArray()) == Encoding
                      .encodeToHex(request.digest.toByteArray()),
                    (),
                    "Checkpoint message already in log"
                  )
                ).getOrElse(Validated.valid(()))
              )
            _ <- (reqSignCheck, waterMarkCheck, isInLogCheck)
              .mapN((_, _, _) => ())
              .fold(
                errors =>
                  error"Error handling checkpoint request: ${errors.toList.mkString(", ")}",
                _ =>
                  storageApi.insertCheckpointMessage(
                    request
                  ) >> updateCheckpointInMemory(
                    request
                  )
              )
          } yield Empty()

        }

        private def handleStableCheckpoint(request: CheckpointRequest) = {
          import co.topl.bridge.consensus.core.pbft.createStateDigestAux
          for {
            lastStableCheckpoint <- lastStableCheckpointRef.underlying.get
            _ <-
              if (
                lastStableCheckpoint.sequenceNumber == request.sequenceNumber &&
                Encoding.encodeToHex(
                  createStateDigestAux(lastStableCheckpoint.state)
                ) == Encoding.encodeToHex(request.digest.toByteArray())
              )
                lastStableCheckpointRef.underlying.set(
                  lastStableCheckpoint.copy(
                    certificates = lastStableCheckpoint.certificates + (
                      request.replicaId -> request
                    )
                  )
                )
              else ().pure[F]
          } yield ()
        }

        private def handleUnstableCheckpoint(
            request: CheckpointRequest
        ): F[(Boolean, Map[Int, CheckpointRequest], Map[String, PBFTState])] = {
          for {
            unstableCheckpoints <- unstableCheckpointsRef.underlying.get
            someCheckpointVotes = unstableCheckpoints.get(
              request.sequenceNumber -> Encoding.encodeToHex(
                request.digest.toByteArray()
              )
            )
            newVotes <- someCheckpointVotes match {
              case None =>
                unstableCheckpointsRef.underlying.set(
                  unstableCheckpoints + ((request.sequenceNumber -> Encoding
                    .encodeToHex(
                      request.digest.toByteArray()
                    )) -> Map(request.replicaId -> request))
                ) >>
                  Map(
                    request.replicaId -> request
                  ).pure[F]
              case Some(checkpointVotes) =>
                unstableCheckpointsRef.underlying.set(
                  unstableCheckpoints + ((request.sequenceNumber -> Encoding
                    .encodeToHex(
                      request.digest.toByteArray()
                    )) -> (checkpointVotes + (request.replicaId -> request)))
                ) >>
                  (checkpointVotes + (request.replicaId -> request)).pure[F]
            }
            seqAndState <- latestStateSnapshotRef.state.get
            (sequenceNumber, stateSnapshotDigest, state) = seqAndState
          } yield {
            val haveNewStableState = newVotes.size > replicaCount.maxFailures &&
              sequenceNumber == request.sequenceNumber &&
              stateSnapshotDigest == Encoding.encodeToHex(
                request.digest.toByteArray()
              )
            (haveNewStableState, newVotes, state)
          }
        }

        def handleNewStableCheckpoint(
            request: CheckpointRequest,
            certificates: Map[Int, CheckpointRequest],
            state: Map[String, PBFTState]
        ): F[Unit] = {
          for {
            _ <- lastStableCheckpointRef.underlying.update(x =>
              x.copy(
                sequenceNumber = request.sequenceNumber,
                certificates = certificates,
                state = state
              )
            )
            lowAndHigh <- watermarkRef.lowAndHigh.get
            (lowWatermark, highWatermark) = lowAndHigh
            _ <- watermarkRef.lowAndHigh.set(
              (
                request.sequenceNumber,
                request.sequenceNumber + kWatermark.underlying
              )
            )
            _ <- unstableCheckpointsRef.underlying.update(x =>
              x.filter(_._1._1 < request.sequenceNumber)
            )
            _ <- storageApi.cleanLog(request.sequenceNumber)
          } yield ()
        }

        private def updateCheckpointInMemory(
            request: CheckpointRequest
        ): F[Unit] = {
          for {
            _ <- handleStableCheckpoint(request)
            triplet <- handleUnstableCheckpoint(request)
            (haveNewStableState, certificates, state) = triplet
            _ <-
              if (haveNewStableState) {
                for {
                  _ <- handleNewStableCheckpoint(
                    request,
                    certificates,
                    state
                  )
                } yield ()
              } else Async[F].unit
          } yield ()
        }

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
