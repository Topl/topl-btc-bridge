package co.topl.bridge.publicapi

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import cats.effect.std.CountDownLatch
import co.topl.bridge.consensus.service.MintingStatusOperation
import co.topl.bridge.consensus.service.StartSessionOperation
import co.topl.bridge.consensus.service.StateMachineRequest
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.bridge.publicapi.Main.ConsensusClientMessageId
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
      messagesMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        CountDownLatch[F]
      ],
      messageResponseMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        Ref[F, Either[
          BridgeError,
          BridgeResponse
        ]]
      ],
      channel: ManagedChannel
      // address: String,
      // port: Int,
      // secureConnection: Boolean
  ) = {

    // val channel = ManagedChannelBuilder
    //   .forAddress(address, port)
    for {
      // channel <-
      //   (if (secureConnection) channel.useTransportSecurity()
      //    else channel.usePlaintext()).resource[F]
      client <- StateMachineServiceFs2Grpc.stubResource(
        channel
      )
    } yield new ConsensusClientGrpc[F] {

      import co.topl.shared.implicits._
      import cats.implicits._

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
          latch <- CountDownLatch[F](1)
          _ <- Sync[F].delay(
            messagesMap.put(
              ConsensusClientMessageId(request.timestamp),
              latch
            )
          )
          ref <- Ref.of[F, Either[BridgeError, BridgeResponse]](
            Left(TimeoutError("No response from backend"))
          )
          _ <- Sync[F].delay(
            messageResponseMap.put(
              ConsensusClientMessageId(request.timestamp),
              ref
            )
          )
          _ <- client.executeRequest(request, new Metadata())
          _ <- trace"Waiting for response from backend"
          _ <- latch.await
          someResponse <- Sync[F].delay(
            messageResponseMap.get(
              ConsensusClientMessageId(request.timestamp)
            )
          )
          res <- someResponse.get
        } yield res).handleErrorWith { e =>
          error"Error in minting status request: ${e.getMessage()}" >> Sync[F]
            .pure(Left(shared.UnknownError("Error in start pegin request")))
        }
      }

      private def prepareRequest(
          operation: StateMachineRequest.Operation
      )(implicit
          clientNumber: ClientNumber
      ) = {
        for {
          timestamp <- Sync[F].delay(System.currentTimeMillis())
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
          latch <- CountDownLatch[F](1)
          _ <- Sync[F].delay(
            messagesMap.put(
              ConsensusClientMessageId(request.timestamp),
              latch
            )
          )
          ref <- Ref.of[F, Either[BridgeError, BridgeResponse]](
            Left(TimeoutError("No response from backend"))
          )
          _ <- Sync[F].delay(
            messageResponseMap.put(
              ConsensusClientMessageId(request.timestamp),
              ref
            )
          )
          _ <- client.executeRequest(request, new Metadata())
          _ <- trace"Waiting for response from backend"
          _ <- latch.await
          someResponse <- Sync[F].delay(
            messageResponseMap.get(
              ConsensusClientMessageId(request.timestamp)
            )
          )
          res <- someResponse.get
        } yield res).handleErrorWith { e =>
          e.printStackTrace()
          error"Error in start pegin request: ${e.getMessage()}" >> Sync[F]
            .pure(Left(shared.UnknownError("Error in start pegin request")))
        }
      }

    }
  }
}
