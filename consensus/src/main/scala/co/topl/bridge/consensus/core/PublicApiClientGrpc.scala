package co.topl.bridge.consensus.core

import cats.effect.kernel.Async
import co.topl.bridge.shared.Empty
import co.topl.bridge.consensus.service.ResponseServiceFs2Grpc
import co.topl.bridge.consensus.service.StateMachineReply
import co.topl.bridge.shared.BridgeCryptoUtils
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

import java.security.KeyPair
import co.topl.bridge.shared.ReplicaId

trait PublicApiClientGrpc[F[_]] {

  def replyStartPegin(
      timestamp: Long,
      currentView: Long,
      startSessionRes: StateMachineReply.Result
  ): F[Empty]

}

object PublicApiClientGrpcImpl {

  def make[F[_]: Async: Logger](
      channel: ManagedChannel,
      keyPair: KeyPair
  )(implicit replicaId: ReplicaId) = {
    for {
      client <- ResponseServiceFs2Grpc.stubResource(channel)
    } yield new PublicApiClientGrpc[F] {
      import cats.implicits._

      import co.topl.bridge.shared.implicits._

      private def prepareRequest(
          timestamp: Long,
          currentView: Long,
          operation: StateMachineReply.Result
      ) = {
        val request = StateMachineReply(
          viewNumber = currentView,
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
          currentView: Long,
          startSessionRes: StateMachineReply.Result
      ): F[Empty] = {

        for {
          _ <- trace"Replying to start pegin request"
          request <- prepareRequest(
            timestamp,
            currentView,
            startSessionRes
          )
          _ <- client.deliverResponse(request, new Metadata())
        } yield Empty()
      }
    }
  }

}
