package co.topl.bridge.consensus

import co.topl.bridge.consensus.service.StartSessionRes
import co.topl.bridge.consensus.service.Empty
import co.topl.bridge.consensus.service.StateMachineReply
import cats.effect.kernel.Async
import java.util.concurrent.ConcurrentHashMap
import cats.effect.kernel.Ref
import java.security.KeyPair
import io.grpc.ManagedChannelBuilder
import co.topl.bridge.consensus.service.ResponseServiceFs2Grpc
import io.grpc.Metadata
import cats.effect.kernel.Sync
import co.topl.shared.BridgeCryptoUtils
import com.google.protobuf.ByteString

trait PublicApiClientGrpc[F[_]] {

  def replyStartPegin(
      timestamp: Long,
      startSessionRes: StateMachineReply.Result
  ): F[Empty]

}

object PublicApiClientGrpcImpl {

  def make[F[_]: Async](
      keyPair: KeyPair,
      messageResponseMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        Ref[F, StateMachineReply.Result]
      ],
      address: String,
      port: Int,
      secureConnection: Boolean
  )(implicit replicaId: ReplicaId) = {
    import fs2.grpc.syntax.all._
    import scala.language.existentials
    val channel = ManagedChannelBuilder
      .forAddress(address, port)
    for {
      channel <-
        (if (secureConnection) channel.useTransportSecurity()
         else channel.usePlaintext()).resource[F]
      client <- ResponseServiceFs2Grpc.stubResource(
        channel
      )
    } yield new PublicApiClientGrpc[F] {
      import cats.implicits._

      import co.topl.shared.implicits._

      private def prepareRequest(
          timestamp: Long,
          operation: StateMachineReply.Result
      ) = {
        val request = StateMachineReply(
          timestamp = timestamp,
          replicaNumber = replicaId.id,
          result = operation
        )
        for {
          signedBytes <- BridgeCryptoUtils.signBytes(
            keyPair.getPrivate(),
            request.signableBytes
          )
          signedRequest = request.copy(signature =
            ByteString.copyFrom(signedBytes)
          )
        } yield signedRequest
      }

      def replyStartPegin(
          timestamp: Long,
          startSessionRes: StateMachineReply.Result
      ): F[Empty] = {

        for {
          request <- prepareRequest(
            timestamp,
            startSessionRes
          )
          _ <- Sync[F].delay(
            client.deliverResponse(request, new Metadata())
          )
        } yield Empty()

      }
    }
  }

}
