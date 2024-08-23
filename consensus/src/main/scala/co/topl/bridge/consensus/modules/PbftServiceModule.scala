package co.topl.bridge.consensus.modules

import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PBFTInternalServiceFs2Grpc
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.shared.Empty
import co.topl.shared.BridgeCryptoUtils
import co.topl.shared.ReplicaCount
import co.topl.shared.implicits._
import cats.implicits._
import io.grpc.Metadata
import org.typelevel.log4cats.Logger

import java.security.MessageDigest
import java.security.PublicKey
import cats.data.Validated
import co.topl.bridge.consensus.persistence.StorageApi
import co.topl.brambl.utils.Encoding
import co.topl.consensus.PBFTProtocolClientGrpc
import co.topl.bridge.consensus.ReplicaId
import java.security.KeyPair
import com.google.protobuf.ByteString
import cats.effect.kernel.Async

trait PbftServiceModule {

  def pbftService[F[_]: Async: Logger](
      pbftProtocolClientGrpc: PBFTProtocolClientGrpc[F],
      keyPair: KeyPair,
      replicaKeysMap: Map[Int, PublicKey],
      clientKeysMap: Map[Int, PublicKey],
      currentView: Ref[F, Long]
  )(implicit
      storageApi: StorageApi[F],
      replica: ReplicaId,
      replicaCount: ReplicaCount
  ) =
    new PBFTInternalServiceFs2Grpc[F, Metadata] {

      private def checkRequestSignatures(
          request: PrePrepareRequest
      ): F[Boolean] = {
        val publicKey = clientKeysMap(request.payload.get.clientNumber)
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
          currentView <- currentView.get
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
          currentView <- currentView.get
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
        import scala.concurrent.duration._
        for {
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
          _ <- storageApi.insertPrepareMessage(prepareRequestSigned)
          _ <- (Async[F].sleep(2.second) >> isPrepared[F](
            request.viewNumber,
            request.sequenceNumber
          )).iterateUntil(identity)
          commitRequest = CommitRequest(
            viewNumber = request.viewNumber,
            sequenceNumber = request.sequenceNumber,
            digest = request.digest,
            replicaId = replica.id
          )
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            commitRequest.signableBytes
          )
          _ <- pbftProtocolClientGrpc.commit(
            commitRequest.withSignature(
              ByteString.copyFrom(signedBytes)
            )
          )
          _ <- storageApi.insertCommitMessage(commitRequest)
          _ <- (Async[F].sleep(2.second) >> isCommitted[F](
            request.viewNumber,
            request.sequenceNumber
          )).iterateUntil(identity)
        } yield Empty()
      }

      override def prepare(request: PrepareRequest, ctx: Metadata): F[Empty] = {
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

}
