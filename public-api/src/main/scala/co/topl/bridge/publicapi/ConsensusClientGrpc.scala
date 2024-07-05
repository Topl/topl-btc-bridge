package co.topl.bridge.publicapi

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import co.topl.bridge.consensus.service.servces.StartSessionOperation
import co.topl.bridge.consensus.service.servces.StateMachineRequest
import co.topl.bridge.consensus.service.servces.StateMachineServiceGrpc
import co.topl.shared.BridgeCryptoUtils
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder

trait ConsensusClientGrpc[F[_]] {

  def startPegin(
      startSessionOperation: StartSessionOperation
  )(implicit
      clientNumber: ClientNumber
  ): F[Unit]
}

object ConsensusClientGrpcImpl {


  def make[F[_]: Async](
      privKeyFilePath: String,
      address: String,
      port: Int,
      secureConnection: Boolean
  ) = {

    import cats.implicits._

    def channelResource[F[_]: Sync](
        address: String,
        port: Int,
        secureConnection: Boolean
    ) =
      Resource
        .make {
          Sync[F].delay(
            if (secureConnection)
              ManagedChannelBuilder
                .forAddress(address, port)
                .build
            else
              ManagedChannelBuilder
                .forAddress(address, port)
                .usePlaintext()
                .build
          )
        }(channel => Sync[F].delay(channel.shutdown()))

    for {
      channel <- channelResource(address, port, secureConnection).allocated
      privKey <- BridgeCryptoUtils.getPrivateKeyFromPemFile(privKeyFilePath)
      client = StateMachineServiceGrpc.stub(channel._1)
    } yield new ConsensusClientGrpc[F] {

      import co.topl.bridge.publicapi.implicits._

      private def prepareRequest(
          operation: StateMachineRequest.Operation
      )(implicit
          clientNumber: ClientNumber
      ) = for {
        timestamp <- Sync[F].delay(System.currentTimeMillis())
        clientNumber <- Sync[F].delay(clientNumber.value)
        request = StateMachineRequest(
          timestamp = timestamp,
          clientNumber = clientNumber,
          operation = operation
        )
        signableBytes = request.signableBytes
        signedBytes <- BridgeCryptoUtils.signBytes(privKey, signableBytes)
        signedRequest = request.copy(signature =
          ByteString.copyFrom(signableBytes)
        )
      } yield signedRequest

      override def startPegin(
          startSessionOperation: StartSessionOperation
      )(implicit
          clientNumber: ClientNumber
      ): F[Unit] = {
        for {
          request <- prepareRequest(
            StateMachineRequest.Operation.StartSession(startSessionOperation)
          )
          _ <- Sync[F].delay(client.executeRequest(request))
        } yield ()
      }

    }
  }
}
