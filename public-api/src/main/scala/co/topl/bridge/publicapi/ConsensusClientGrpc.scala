package co.topl.bridge.publicapi

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import co.topl.bridge.consensus.service.MintingStatusOperation
import co.topl.bridge.consensus.service.StartSessionOperation
import co.topl.bridge.consensus.service.StateMachineRequest
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.bridge.publicapi.ConsensusClientMessageId
import co.topl.shared
import co.topl.shared.BridgeCryptoUtils
import co.topl.shared.BridgeError
import co.topl.shared.BridgeResponse
import co.topl.shared.TimeoutError
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

trait ConsensusClientGrpc[F[_]] {

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

  def make[F[_]: Async: Logger](
      keyPair: KeyPair,
      messageResponseMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        ConcurrentHashMap[Either[
          BridgeError,
          BridgeResponse
        ], LongAdder]
      ],
      channel: ManagedChannel
  )(implicit replicaCount: ReplicaCount) = {

    for {
      client <- StateMachineServiceFs2Grpc.stubResource(
        channel
      )
    } yield new ConsensusClientGrpc[F] {

      import co.topl.shared.implicits._
      import cats.implicits._
      import scala.concurrent.duration._

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
                Async[F].delay(
                  messageResponseMap.remove(
                    ConsensusClientMessageId(timestamp)
                  )
                ) >> Async[F].delay(
                  voteTable.clear()
                ) >> Async[F].delay(
                  winner.getKey()
                )
            case None => // there are no votes
              trace"No votes yet" >> Async[F].sleep(
                2.second
              ) >> checkVoteResult(timestamp)
          })
        } yield winner
      }

      override def mintingStatus(
          mintingStatusOperation: MintingStatusOperation
      )(implicit
          clientNumber: ClientNumber
      ): F[Either[BridgeError, BridgeResponse]] = {
        (for {
          _ <- trace"Preparing request..."
          request <- prepareRequest(
            StateMachineRequest.Operation.MintingStatus(mintingStatusOperation)
          )
          _ <- trace"Sending start minting status request to backend"
          _ <- Async[F].delay(
            messageResponseMap.putIfAbsent(
              ConsensusClientMessageId(request.timestamp),
              new ConcurrentHashMap[Either[
                BridgeError,
                BridgeResponse
              ], LongAdder]()
            )
          )
          _ <- client.executeRequest(request, new Metadata())
          _ <- trace"Waiting for response from backend"
          // count votes
          res <- checkVoteResult(
            request.timestamp
          )
          // res <- someResponse.get
        } yield res).handleErrorWith { e =>
          error"Error in minting status request: ${e.getMessage()}" >>
            (shared.UnknownError("Error in start pegin request"): BridgeError)
              .asLeft[BridgeResponse]
              .pure[F]
        }
      }

      private def prepareRequest(
          operation: StateMachineRequest.Operation
      )(implicit
          clientNumber: ClientNumber
      ): F[StateMachineRequest] = {
        for {
          timestamp <- Async[F].delay(System.currentTimeMillis())
          request = StateMachineRequest(
            timestamp = timestamp,
            clientNumber = clientNumber.value,
            operation = operation
          )
          signableBytes = request.signableBytes
          signedBytes <- BridgeCryptoUtils.signBytes(
            keyPair.getPrivate(),
            signableBytes
          )
          signedRequest = request.copy(signature =
            ByteString.copyFrom(signableBytes)
          )
        } yield signedRequest
      }
      override def startPegin(
          startSessionOperation: StartSessionOperation
      )(implicit
          clientNumber: ClientNumber
      ): F[Either[BridgeError, BridgeResponse]] = {
        (for {
          request <- prepareRequest(
            StateMachineRequest.Operation.StartSession(startSessionOperation)
          )
          _ <- info"Sending start pegin request to backend"
          _ <- Sync[F].delay(
            messageResponseMap.put(
              ConsensusClientMessageId(request.timestamp),
              new ConcurrentHashMap[Either[
                BridgeError,
                BridgeResponse
              ], LongAdder]()
            )
          )
          _ <- client.executeRequest(request, new Metadata())
          _ <- trace"Waiting for response from backend"
          _ <- trace"channel is shutdown: ${channel.isShutdown()}"
          someResponse <- Async[F].race(
            Async[F].sleep(10.second) >> // wait for response
              (TimeoutError("Timeout waiting for response"): BridgeError)
                .pure[F],
            checkVoteResult(request.timestamp)
          )
        } yield someResponse.flatten)
          .handleErrorWith { e =>
            e.printStackTrace()
            error"Error in start pegin request: ${e.getMessage()}" >> Async[F]
              .pure(Left(shared.UnknownError("Error in start pegin request")))
          }
      }

    }
  }
}
