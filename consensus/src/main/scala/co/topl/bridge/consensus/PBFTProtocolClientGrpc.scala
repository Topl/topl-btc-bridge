package co.topl.consensus

import cats.effect.kernel.Async
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PBFTInternalServiceFs2Grpc
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.shared.Empty
import co.topl.shared.ReplicaNode
import fs2.grpc.syntax.all._
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

trait PBFTProtocolClientGrpc[F[_]] {

  def prePrepare(
      request: PrePrepareRequest
  ): F[Empty]

  def prepare(
      request: PrepareRequest
  ): F[Empty]

  def commit(
      request: CommitRequest
  ): F[Empty]

}

object PBFTProtocolClientGrpcImpl {

  import cats.implicits._

  def make[F[_]: Async: Logger](
      replicaNodes: List[ReplicaNode[F]]
  ) = {
    for {
      idBackupMap <- (for {
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
          consensusClient <- PBFTInternalServiceFs2Grpc.stubResource(
            channel
          )
        } yield (replicaNode.id -> consensusClient)
      }).sequence
      backupMap = idBackupMap.toMap
    } yield new PBFTProtocolClientGrpc[F] {

      override def commit(request: CommitRequest): F[Empty] =
        for {
          _ <- trace"Sending CommitRequest to all replicas"
          _ <- backupMap.toList.traverse { case (_, backup) =>
            backup.commit(request, new Metadata())
          }
        } yield Empty()

      override def prePrepare(request: PrePrepareRequest): F[Empty] =
        for {
          _ <- trace"Sending PrePrepareRequest to all replicas"
          _ <- backupMap.toList.traverse { case (_, backup) =>
            backup.prePrepare(request, new Metadata())
          }
        } yield Empty()

      override def prepare(
          request: PrepareRequest
      ): F[Empty] = {
        for {
          _ <- trace"Sending PrepareRequest to all replicas"
          _ <- backupMap.toList.traverse { case (_, backup) =>
            backup.prepare(request, new Metadata())
          }
        } yield Empty()
      }

    }
  }
}
