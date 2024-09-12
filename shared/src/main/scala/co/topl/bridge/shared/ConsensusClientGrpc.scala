package co.topl.bridge.shared

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import co.topl.bridge.shared.MintingStatusOperation
import co.topl.bridge.shared.StartSessionOperation
import co.topl.bridge.shared.StateMachineRequest
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.bridge.shared.BridgeCryptoUtils
import co.topl.bridge.shared.BridgeError
import co.topl.bridge.shared.BridgeResponse
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaNode
import co.topl.bridge.shared.TimeoutError
import com.google.protobuf.ByteString
import fs2.grpc.syntax.all._
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import cats.effect.std.Mutex
import co.topl.bridge.shared.PostDepositBTCOperation
import co.topl.bridge.shared.TimeoutDepositBTCOperation
import co.topl.bridge.shared.UndoDepositBTCOperation
import co.topl.bridge.shared.ConfirmDepositBTCOperation
import co.topl.bridge.shared.PostTBTCMintOperation
import co.topl.bridge.shared.TimeoutTBTCMintOperation
import co.topl.bridge.shared.UndoTBTCMintOperation
import co.topl.bridge.shared.ConfirmTBTCMintOperation
import co.topl.bridge.shared.PostRedemptionTxOperation
import co.topl.bridge.shared.PostClaimTxOperation
import co.topl.bridge.shared.ConfirmClaimTxOperation
import co.topl.bridge.shared.UndoClaimTxOperation
import co.topl.bridge.consensus.service.MintingStatusReply

trait ConsensusClientGrpc[F[_]] {

  def startPegin(
      startSessionOperation: StartSessionOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def postDepositBTC(
      postDepositBTCOperation: PostDepositBTCOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def timeoutDepositBTC(
      timeoutDepositBTCOperation: TimeoutDepositBTCOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def undoDepositBTC(
      undoDepositBTCOperation: UndoDepositBTCOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def confirmDepositBTC(
      confirmDepositBTCOperation: ConfirmDepositBTCOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def postTBTCMint(
      postTBTCMintOperation: PostTBTCMintOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def timeoutTBTCMint(
      timeoutTBTCMintOperation: TimeoutTBTCMintOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def undoTBTCMint(
      undoTBTCMintOperation: UndoTBTCMintOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def confirmTBTCMint(
      confirmTBTCMintOperation: ConfirmTBTCMintOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def postRedemptionTx(
      postRedemptionTxOperation: PostRedemptionTxOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def postClaimTx(
      postClaimTxOperation: PostClaimTxOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def confirmClaimTx(
      confirmClaimTxOperation: ConfirmClaimTxOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def undoClaimTx(
      undoClaimTxOperation: UndoClaimTxOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]

  def mintingStatus(
      mintingStatusOperation: MintingStatusOperation
  )(implicit
      clientNumber: ClientId
  ): F[Either[BridgeError, BridgeResponse]]
}

object ConsensusClientGrpcImpl {

  import cats.implicits._
  import co.topl.bridge.shared.implicits._
  import scala.concurrent.duration._

  def makeContainer[F[_]: Async: Logger](
      currentViewRef: Ref[F, Long],
      keyPair: KeyPair,
      mutex: Mutex[F],
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
      replicaMap = idClientList.toMap
    } yield new ConsensusClientGrpc[F] {

      def undoClaimTx(
          undoClaimTxOperation: UndoClaimTxOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] =
        mutex.lock.surround(for {
          request <- prepareRequest(
            StateMachineRequest.Operation.UndoClaimTx(
              undoClaimTxOperation
            )
          )
          response <- executeRequest(request)
        } yield response)

      def confirmClaimTx(
          confirmClaimTxOperation: ConfirmClaimTxOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] =
        mutex.lock.surround(for {
          request <- prepareRequest(
            StateMachineRequest.Operation.ConfirmClaimTx(
              confirmClaimTxOperation
            )
          )
          response <- executeRequest(request)
        } yield response)

      def postClaimTx(
          postClaimTxOperation: PostClaimTxOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] =
        mutex.lock.surround(for {
          request <- prepareRequest(
            StateMachineRequest.Operation.PostClaimTx(
              postClaimTxOperation
            )
          )
          response <- executeRequest(request)
        } yield response)

      def postRedemptionTx(
          postRedemptionTxOperation: PostRedemptionTxOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] =
        mutex.lock.surround(for {
          request <- prepareRequest(
            StateMachineRequest.Operation.PostRedemptionTx(
              postRedemptionTxOperation
            )
          )
          response <- executeRequest(request)
        } yield response)

      def confirmTBTCMint(
          confirmTBTCMintOperation: ConfirmTBTCMintOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] = mutex.lock.surround(for {
        request <- prepareRequest(
          StateMachineRequest.Operation.ConfirmTBTCMint(
            confirmTBTCMintOperation
          )
        )
        response <- executeRequest(request)
      } yield response)

      def undoTBTCMint(
          undoTBTCMintOperation: UndoTBTCMintOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] = mutex.lock.surround(for {
        request <- prepareRequest(
          StateMachineRequest.Operation.UndoTBTCMint(
            undoTBTCMintOperation
          )
        )
        response <- executeRequest(request)
      } yield response)

      def timeoutTBTCMint(
          timeoutTBTCMintOperation: TimeoutTBTCMintOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] = mutex.lock.surround(for {
        request <- prepareRequest(
          StateMachineRequest.Operation.TimeoutTBTCMint(
            timeoutTBTCMintOperation
          )
        )
        response <- executeRequest(request)
      } yield response)

      def postTBTCMint(
          postTBTCMintOperation: PostTBTCMintOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] = mutex.lock.surround(for {
        request <- prepareRequest(
          StateMachineRequest.Operation.PostTBTCMint(
            postTBTCMintOperation
          )
        )
        response <- executeRequest(request)
      } yield response)

      def confirmDepositBTC(
          confirmDepositBTCOperation: ConfirmDepositBTCOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] = mutex.lock.surround(for {
        request <- prepareRequest(
          StateMachineRequest.Operation.ConfirmDepositBTC(
            confirmDepositBTCOperation
          )
        )
        response <- executeRequest(request)
      } yield response)

      override def undoDepositBTC(
          undoDepositBTCOperation: UndoDepositBTCOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] = mutex.lock.surround(for {
        request <- prepareRequest(
          StateMachineRequest.Operation.UndoDepositBTC(
            undoDepositBTCOperation
          )
        )
        response <- executeRequest(request)
      } yield response)

      override def timeoutDepositBTC(
          timeoutDepositBTCOperation: TimeoutDepositBTCOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] =
        mutex.lock.surround(for {
          request <- prepareRequest(
            StateMachineRequest.Operation.TimeoutDepositBTC(
              timeoutDepositBTCOperation
            )
          )
          response <- executeRequest(request)
        } yield response)

      override def postDepositBTC(
          postDepositBTCOperation: PostDepositBTCOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] = mutex.lock.surround(for {
        request <- prepareRequest(
          StateMachineRequest.Operation.PostDepositBTC(postDepositBTCOperation)
        )
        response <- executeRequest(request)
      } yield response)

      def startPegin(
          startSessionOperation: StartSessionOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] =
        mutex.lock.surround(for {
          request <- prepareRequest(
            StateMachineRequest.Operation.StartSession(startSessionOperation)
          )
          response <- executeRequest(request)
        } yield response)

      def mintingStatus(
          mintingStatusOperation: MintingStatusOperation
      )(implicit
          clientNumber: ClientId
      ): F[Either[BridgeError, BridgeResponse]] = {
        import cats.implicits._
        mutex.lock.surround(
          for {
            currentView <- currentViewRef.get
            _ <- info"Current view is $currentView"
            _ <- info"Replica count is ${replicaCount.value}"
            currentPrimary = (currentView % replicaCount.value).toInt
            response <- replicaMap(currentPrimary)
              .mintingStatus(mintingStatusOperation, new Metadata())
          } yield response.result match {
            case MintingStatusReply.Result.Empty =>
              Left(
                UnknownError(
                  "This should not happen: Empty response"
                )
              )
            case MintingStatusReply.Result.SessionNotFound(value) =>
              Left(SessionNotFoundError(value.sessionId))
            case MintingStatusReply.Result.MintingStatus(response) =>
              Right {
                MintingStatusResponse(
                  mintingStatus = response.mintingStatus,
                  address = response.address,
                  redeemScript = response.redeemScript
                )
              }
          }
        )
      }

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
                ) >> checkVoteResult(timestamp)
              } else
                clearVoteTable(timestamp) >> Async[F].delay(winner.getKey())
            case None => // there are no votes
              trace"No votes yet" >> Async[F].sleep(
                2.second
              ) >> checkVoteResult(timestamp)
          })
        } yield winner
      }

      def executeRequest(
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
          _ <- info"Current view is $currentView"
          _ <- info"Replica count is ${replicaCount.value}"
          currentPrimary = (currentView % replicaCount.value).toInt
          _ <- info"Current primary is $currentPrimary"
          _ <- info"Replica map: $replicaMap"
          _ <- replicaMap(currentPrimary).executeRequest(
            request,
            new Metadata()
          )
          _ <- trace"Waiting for response from backend"
          someResponse <- Async[F].race(
            Async[F].sleep(10.second) >> // wait for response
              replicaMap
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

      def prepareRequest(
          operation: StateMachineRequest.Operation
      )(implicit clientNumber: ClientId): F[StateMachineRequest] =
        for {
          timestamp <- Async[F].delay(System.currentTimeMillis())
          request = StateMachineRequest(
            timestamp = timestamp,
            clientNumber = clientNumber.id,
            operation = operation
          )
          signableBytes = request.signableBytes
          signedBytes <- BridgeCryptoUtils.signBytes[F](
            keyPair.getPrivate(),
            signableBytes
          )
          signedRequest = request.withSignature(
            ByteString.copyFrom(signedBytes)
          )
        } yield signedRequest
    }

  }
}
