package co.topl.bridge.publicapi

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import co.topl.bridge.consensus.service.StartSessionOperation
import co.topl.bridge.consensus.service.StateMachineRequest
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.shared.BridgeCryptoUtils
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import java.util.concurrent.ConcurrentHashMap
import co.topl.bridge.publicapi.Main.ConsensusClientMessageId
import cats.effect.std.CountDownLatch
import java.security.KeyPair
import cats.effect.kernel.Ref
import co.topl.shared.StartPeginSessionResponse

trait ConsensusClientGrpc[F[_]] {

  def startPegin(
      startSessionOperation: StartSessionOperation
  )(implicit
      clientNumber: ClientNumber
  ): F[Option[StartPeginSessionResponse]]
}

object ConsensusClientGrpcImpl {

  def make[F[_]: Async](
      keyPair: KeyPair,
      messagesMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        CountDownLatch[F]
      ],
      messageResponseMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        Ref[F, Option[
          StartPeginSessionResponse
        ]]
      ],
      address: String,
      port: Int,
      secureConnection: Boolean
  ) = {

    import fs2.grpc.syntax.all._
    import scala.language.existentials
    val channel = ManagedChannelBuilder
      .forAddress(address, port)
    for {
      channel <-
        (if (secureConnection) channel.useTransportSecurity()
         else channel.usePlaintext()).resource[F]
      client <- StateMachineServiceFs2Grpc.stubResource(
        channel
      )
    } yield new ConsensusClientGrpc[F] {

      import co.topl.shared.implicits._
      import cats.implicits._

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
      ): F[Option[StartPeginSessionResponse]] = {
        for {
          request <- prepareRequest(
            StateMachineRequest.Operation.StartSession(startSessionOperation)
          )
          _ <- Sync[F].delay(
            client.executeRequest(request, new Metadata())
          )
          latch <- CountDownLatch[F](1)
          _ <- Sync[F].delay(
            messagesMap.put(
              ConsensusClientMessageId(request.timestamp),
              latch
            )
          )
          _ <- latch.await
          someResponse <- Sync[F].delay(
            messageResponseMap.get(
              ConsensusClientMessageId(request.timestamp)
            )
          )
          res <- someResponse.get
        } yield res
      }

    }
  }
}
