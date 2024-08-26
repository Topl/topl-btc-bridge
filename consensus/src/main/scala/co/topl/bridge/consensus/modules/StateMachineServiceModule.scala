package co.topl.bridge.consensus.modules

import cats.effect.IO
import cats.effect.kernel.Ref
import co.topl.bridge.consensus.PublicApiClientGrpc
import co.topl.bridge.consensus.ReplicaId
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.persistence.StorageApi
import co.topl.bridge.consensus.service.StateMachineReply.Result
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.bridge.shared.Empty
import co.topl.consensus.PBFTProtocolClientGrpc
import co.topl.shared.BridgeCryptoUtils
import co.topl.shared.ClientId
import co.topl.shared.ReplicaCount
import com.google.protobuf.ByteString
import io.grpc.Metadata
import org.typelevel.log4cats.Logger

import java.security.MessageDigest
import java.security.PublicKey
import java.security.{KeyPair => JKeyPair}
import java.util.concurrent.ConcurrentHashMap

trait StateMachineServiceModule {

  def stateMachineService(
      keyPair: JKeyPair,
      pbftProtocolClientGrpc: PBFTProtocolClientGrpc[IO],
      idReplicaClientMap: Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
      lastReplyMap: ConcurrentHashMap[(ClientId, Long), Result],
      publicApiClientGrpcMap: Map[
        ClientId,
        (PublicApiClientGrpc[IO], PublicKey)
      ],
      currentView: Ref[IO, Long],
      currentSequenceRef: Ref[IO, Long],
  )(implicit
      storageApi: StorageApi[IO],
      replicaId: ReplicaId,
      replicaCount: ReplicaCount,
      logger: Logger[IO]
  ) = StateMachineServiceFs2Grpc.bindServiceResource(
    serviceImpl = new StateMachineServiceFs2Grpc[IO, Metadata] {
      // log4cats syntax
      import org.typelevel.log4cats.syntax._

      def executeRequest(
          request: co.topl.bridge.shared.StateMachineRequest,
          ctx: Metadata
      ): IO[Empty] = {
        Option(
          lastReplyMap.get((ClientId(request.clientNumber), request.timestamp))
        ) match {
          case Some(result) => // we had a cached response
            for {
              viewNumber <- currentView.get
              _ <- debug"Request.clientNumber: ${request.clientNumber}"
              _ <- publicApiClientGrpcMap(ClientId(request.clientNumber))._1
                .replyStartPegin(request.timestamp, viewNumber, result)
            } yield Empty()
          case None =>
            import scala.concurrent.duration._
            for {
              currentView <- currentView.get
              currentSequence <- currentSequenceRef.updateAndGet(_ + 1)
              currentPrimary = currentView % replicaCount.value
              _ <-
                if (currentPrimary != replicaId.id)
                  // we are not the primary, forward the request
                  idReplicaClientMap(
                    replicaId.id
                  ).executeRequest(request, ctx)
                else {
                  import co.topl.shared.implicits._
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
                    _ <- storageApi.insertPrePrepareMessage(
                      signedprePrepareRequest
                    )
                    _ <- pbftProtocolClientGrpc.prepare(
                      signedPrepareRequest
                    )
                    _ <- storageApi.insertPrepareMessage(signedPrepareRequest)
                    _ <- (IO.sleep(2.seconds) >>
                      isPrepared[IO](
                        currentView,
                        currentSequence
                      )).iterateUntil(identity)
                    commitRequest = CommitRequest(
                      viewNumber = currentView,
                      sequenceNumber = currentSequence,
                      digest = prePrepareRequest.digest,
                      replicaId = replicaId.id
                    )
                    signedBytes <- BridgeCryptoUtils.signBytes[IO](
                      keyPair.getPrivate(),
                      commitRequest.signableBytes
                    )
                    _ <- pbftProtocolClientGrpc.commit(
                      commitRequest.withSignature(
                        ByteString.copyFrom(signedBytes)
                      )
                    )
                    _ <- storageApi.insertCommitMessage(commitRequest)
                    _ <- (IO.sleep(2.second) >> isCommitted[IO](
                      currentView,
                      currentSequence
                    )).iterateUntil(identity)
                  } yield ()) >>
                    // we are the primary, process the request
                    // FIXME: add execution
                    ??? >> IO.pure(Empty())
                }
            } yield Empty()

        }
      }
    }
  )

}
