package co.topl.bridge.consensus.modules

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import co.topl.bridge.consensus.CurrentView
import co.topl.bridge.consensus.LastReplyMap
import co.topl.bridge.consensus.PublicApiClientGrpcMap
import co.topl.bridge.consensus.managers.PeginSessionInfo
import co.topl.bridge.consensus.managers.SessionManagerAlgebra
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.service.MintingStatusReply
import co.topl.bridge.consensus.service.MintingStatusReply.{Result => MSReply}
import co.topl.bridge.consensus.service.MintingStatusRes
import co.topl.bridge.consensus.service.SessionNotFoundRes
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.bridge.shared.Empty
import co.topl.bridge.shared.MintingStatusOperation
import co.topl.consensus.PBFTProtocolClientGrpc
import co.topl.bridge.shared.BridgeCryptoUtils
import co.topl.bridge.shared.ClientId
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaId
import com.google.protobuf.ByteString
import io.grpc.Metadata
import org.typelevel.log4cats.Logger

import java.security.MessageDigest
import java.security.{KeyPair => JKeyPair}

trait StateMachineServiceModule {


  def stateMachineService(
      keyPair: JKeyPair,
      pbftProtocolClientGrpc: PBFTProtocolClientGrpc[IO],
      idReplicaClientMap: Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
      currentSequenceRef: Ref[IO, Long]
  )(implicit
      lastReplyMap: LastReplyMap,
      sessionManager: SessionManagerAlgebra[IO],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
      currentViewRef: CurrentView[IO],
      replicaId: ReplicaId,
      replicaCount: ReplicaCount,
      logger: Logger[IO]
  ) = StateMachineServiceFs2Grpc.bindServiceResource(
    serviceImpl = new StateMachineServiceFs2Grpc[IO, Metadata] {

      // log4cats syntax
      import org.typelevel.log4cats.syntax._
      import cats.implicits._

      private def mintingStatusAux[F[_]: Sync](
          value: MintingStatusOperation
      )(implicit
          sessionManager: SessionManagerAlgebra[F]
      ) =
        for {
          session <- sessionManager.getSession(value.sessionId)
          somePegin <- session match {
            case Some(p: PeginSessionInfo) => Sync[F].delay(Option(p))
            case None                      => Sync[F].delay(None)
            case _ =>
              Sync[F].raiseError(new Exception("Invalid session type"))
          }
          resp: MSReply = somePegin match {
            case Some(pegin) =>
              MSReply.MintingStatus(
                MintingStatusRes(
                  sessionId = value.sessionId,
                  mintingStatus = pegin.mintingBTCState.toString(),
                  address = pegin.redeemAddress,
                  redeemScript =
                    s""""threshold(1, sha256(${pegin.sha256}) and height(${pegin.minHeight}, ${pegin.maxHeight}))"""
                )
              )
            case None =>
              MSReply.SessionNotFound(
                SessionNotFoundRes(
                  value.sessionId
                )
              )
          }
        } yield resp

      override def mintingStatus(
          request: MintingStatusOperation,
          ctx: Metadata
      ): IO[MintingStatusReply] =
        mintingStatusAux[IO](request).map(MintingStatusReply(_))

      def executeRequest(
          request: co.topl.bridge.shared.StateMachineRequest,
          ctx: Metadata
      ): IO[Empty] = {
        Option(
          lastReplyMap.underlying.get(
            (ClientId(request.clientNumber), request.timestamp)
          )
        ) match {
          case Some(result) => // we had a cached response
            for {
              viewNumber <- currentViewRef.underlying.get
              _ <- debug"Request.clientNumber: ${request.clientNumber}"
              _ <- publicApiClientGrpcMap
                .underlying(ClientId(request.clientNumber))
                ._1
                .replyStartPegin(request.timestamp, viewNumber, result)
            } yield Empty()
          case None =>
            for {
              currentView <- currentViewRef.underlying.get
              currentSequence <- currentSequenceRef.updateAndGet(_ + 1)
              currentPrimary = currentView % replicaCount.value
              _ <-
                if (currentPrimary != replicaId.id)
                  // we are not the primary, forward the request
                  idReplicaClientMap(
                    replicaId.id
                  ).executeRequest(request, ctx)
                else {
                  import co.topl.bridge.shared.implicits._
                  val prePrepareRequest = PrePrepareRequest(
                    viewNumber = currentView,
                    sequenceNumber = currentSequence,
                    digest = ByteString.copyFrom(
                      MessageDigest
                        .getInstance("SHA-256")
                        .digest(request.signableBytes)
                    ),
                    payload = Some(request)
                  )
                  val prepareRequest = PrepareRequest(
                    viewNumber = currentView,
                    sequenceNumber = currentSequence,
                    digest = prePrepareRequest.digest,
                    replicaId = replicaId.id
                  )
                  (for {
                    signedPrePreparedBytes <- BridgeCryptoUtils.signBytes[IO](
                      keyPair.getPrivate(),
                      prePrepareRequest.signableBytes
                    )
                    signedPreparedBytes <- BridgeCryptoUtils.signBytes[IO](
                      keyPair.getPrivate(),
                      prepareRequest.signableBytes
                    )
                    signedprePrepareRequest = prePrepareRequest.withSignature(
                      ByteString.copyFrom(signedPrePreparedBytes)
                    )
                    signedPrepareRequest = prepareRequest.withSignature(
                      ByteString.copyFrom(signedPreparedBytes)
                    )
                    _ <- pbftProtocolClientGrpc.prePrepare(
                      signedprePrepareRequest
                    )
                  } yield ()) >> IO.pure(Empty())

                }
            } yield Empty()

        }
      }
    }
  )

}
