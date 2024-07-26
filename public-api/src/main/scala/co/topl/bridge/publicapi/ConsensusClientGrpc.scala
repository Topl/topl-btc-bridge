package co.topl.bridge.publicapi

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import co.topl.bridge.consensus.service.MintingStatusOperation
import co.topl.bridge.consensus.service.StartSessionOperation
import co.topl.bridge.consensus.service.StateMachineRequest
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.bridge.publicapi.ConsensusClientMessageId
import co.topl.shared.BridgeCryptoUtils
import co.topl.shared.BridgeError
import co.topl.shared.BridgeResponse
import co.topl.shared.TimeoutError
import com.google.protobuf.ByteString
import fs2.grpc.syntax.all._
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import co.topl.shared.ReplicaNode
import co.topl.shared.ReplicaCount

trait ConsensusClientGrpc[F[_]] {

  def prepareRequest(
      operation: StateMachineRequest.Operation
  )(implicit
      clientNumber: ClientNumber
  ): F[StateMachineRequest]

  def executeRequest(
      request: StateMachineRequest
  ): F[Either[BridgeError, BridgeResponse]]

  def startPegin(
      startSessionOperation: StartSessionOperation
  )(implicit
      clientNumber: ClientNumber
  ): F[Either[BridgeError, BridgeResponse]]

  def mintingStatus(
      mintingStatusOperation: MintingStatusOperation
  )(implicit
      clientNumber: ClientNumber
  ): F[Either[BridgeError, BridgeResponse]]
}

object ConsensusClientGrpcImpl {

  import cats.implicits._
  import co.topl.shared.implicits._
  import scala.concurrent.duration._

  def makeContainer[F[_]: Async: Logger](
      currentViewRef: Ref[F, Long],
      keyPair: KeyPair,
      replicaNodes: List[ReplicaNode[F]],
      messageVotersMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        ConcurrentHashMap[Int, Int]
      ],
      messageResponseMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        ConcurrentHashMap[Either[
          BridgeError,
          BridgeResponse
        ], LongAdder]
      ]
  )(implicit replicaCount: ReplicaCount) = {
    for {
      idClientList <- (for {
        replicaNode <- replicaNodes
      } yield {
        for {
          channel <-
            (if (replicaNode.backendSecure)
               ManagedChannelBuilder
                 .forAddress(replicaNode.backendHost, replicaNode.backendPort)
                 .useTransportSecurity()
             else
               ManagedChannelBuilder
                 .forAddress(replicaNode.backendHost, replicaNode.backendPort)
                 .usePlaintext()).resource[F]
          consensusClient <- StateMachineServiceFs2Grpc.stubResource(
            channel
          )
        } yield (replicaNode.id -> consensusClient)
      }).sequence
      clientMap = idClientList.toMap
    } yield new ConsensusClientGrpc[F] {

      def startPegin(
          startSessionOperation: StartSessionOperation
      )(implicit
          clientNumber: ClientNumber
      ): F[Either[BridgeError, BridgeResponse]] =
        for {
          request <- prepareRequest(
            StateMachineRequest.Operation.StartSession(startSessionOperation)
          )
          response <- executeRequest(request)
        } yield response

      def mintingStatus(
          mintingStatusOperation: MintingStatusOperation
      )(implicit
          clientNumber: ClientNumber
      ): F[Either[BridgeError, BridgeResponse]] =
        for {
          request <- prepareRequest(
            StateMachineRequest.Operation.MintingStatus(mintingStatusOperation)
          )
          response <- executeRequest(request)
        } yield response

      private def clearVoteTable(
          timestamp: Long
      ): F[Unit] = {
        for {
          _ <- Async[F].delay(
            messageResponseMap.remove(
              ConsensusClientMessageId(timestamp)
            )
          )
          _ <- Async[F].delay(
            messageVotersMap.remove(
              ConsensusClientMessageId(timestamp)
            )
          )
        } yield ()
      }

      private def checkVoteResult(
          timestamp: Long
      ): F[Either[BridgeError, BridgeResponse]] = {
        import scala.jdk.CollectionConverters._
        for {
          voteTable <- Async[F].delay(
            messageResponseMap.get(
              ConsensusClientMessageId(timestamp)
            )
          )
          someVotationWinner <- Async[F].delay(
            voteTable
              .entrySet()
              .asScala
              .toList
              .sortBy(_.getValue.longValue())
              .headOption
          )
          winner <- (someVotationWinner match {
            case Some(winner) => // there are votes, check winner
              if (
                winner.getValue.longValue() < (replicaCount.maxFailures + 1)
              ) {
                trace"Waiting for more votes" >> Async[F].sleep(
                  2.second
                ) >> checkVoteResult(
                  timestamp
                )
              } else
                clearVoteTable(timestamp) >> Async[F].delay(winner.getKey())
            case None => // there are no votes
              trace"No votes yet" >> Async[F].sleep(
                2.second
              ) >> checkVoteResult(timestamp)
          })
        } yield winner
      }

      override def executeRequest(
          request: StateMachineRequest
      ): F[Either[BridgeError, BridgeResponse]] =
        for {
          _ <- info"Sending request to backend"
          // create a new vote table for this request
          _ <- Sync[F].delay(
            messageResponseMap.put(
              ConsensusClientMessageId(request.timestamp),
              new ConcurrentHashMap[Either[
                BridgeError,
                BridgeResponse
              ], LongAdder]()
            )
          )
          // create a new voter table for this request
          _ <- Sync[F].delay(
            messageVotersMap.put(
              ConsensusClientMessageId(request.timestamp),
              new ConcurrentHashMap[Int, Int]()
            )
          )
          currentView <- currentViewRef.get
          currentPrimary = (currentView % replicaCount.value).toInt
          _ <- clientMap(currentPrimary).executeRequest(request, new Metadata())
          _ <- trace"Waiting for response from backend"
          someResponse <- Async[F].race(
            Async[F].sleep(10.second) >> // wait for response
              clientMap
                .filter(x =>
                  x._1 != currentPrimary
                ) // send to all replicas except primary
                .map(_._2.executeRequest(request, new Metadata()))
                .toList
                .sequence >>
              Async[F].sleep(10.second) >> // wait for response
              (TimeoutError("Timeout waiting for response"): BridgeError)
                .pure[F],
            checkVoteResult(request.timestamp)
          )
        } yield someResponse.flatten

      override def prepareRequest(
          operation: StateMachineRequest.Operation
      )(implicit clientNumber: ClientNumber): F[StateMachineRequest] =
        for {
          timestamp <- Async[F].delay(System.currentTimeMillis())
          request = StateMachineRequest(
            timestamp = timestamp,
            clientNumber = clientNumber.value,
            operation = operation
          )
          signableBytes = request.signableBytes
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            signableBytes
          )
          signedRequest = request.copy(signature =
            ByteString.copyFrom(signableBytes)
          )
        } yield signedRequest
    }

  }
}

