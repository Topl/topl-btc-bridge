package co.topl.consensus.core

import cats.effect.kernel.Async
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PBFTInternalServiceFs2Grpc
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.shared.Empty
import co.topl.bridge.shared.ReplicaNode
import fs2.grpc.syntax.all._
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import co.topl.bridge.consensus.pbft.CheckpointRequest

trait PBFTInternalGrpcServiceClient[F[_]] {

  def prePrepare(
      request: PrePrepareRequest
  ): F[Empty]

  def prepare(
      request: PrepareRequest
  ): F[Empty]

  def commit(
      request: CommitRequest
  ): F[Empty]

  def checkpoint(
      request: CheckpointRequest
  ): F[Empty]

}

object PBFTInternalGrpcServiceClientImpl {

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
    } yield new PBFTInternalGrpcServiceClient[F] {

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

      override def checkpoint(
          request: CheckpointRequest
      ): F[Empty] = for {
        _ <- trace"Sending Checkpoint to all replicas"
        _ <- backupMap.toList.traverse { case (_, backup) =>
          backup.checkpoint(request, new Metadata())
        }
      } yield Empty()

    }
  }
}
