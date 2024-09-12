package co.topl.bridge.stubs

import cats.effect.IO
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.shared.SessionInfo
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.consensus.subsystems.monitor.BlockchainEvent

class BaseStorageApi extends StorageApi[IO] {

  override def cleanLog(sequenceNumber: Long): IO[Unit] = ???

  override def getPrePrepareMessage(
      viewNumber: Long,
      sequenceNumber: Long
  ): IO[Option[PrePrepareRequest]] = ???

  override def getPrepareMessages(
      viewNumber: Long,
      sequenceNumber: Long
  ): IO[Seq[PrepareRequest]] = ???

  override def getCommitMessages(
      viewNumber: Long,
      sequenceNumber: Long
  ): IO[Seq[CommitRequest]] = ???

  override def getCheckpointMessage(
      sequenceNumber: Long,
      replicaId: Int
  ): IO[Option[CheckpointRequest]] = ???

  override def insertPrePrepareMessage(
      prePrepare: PrePrepareRequest
  ): IO[Boolean] = ???

  override def insertCheckpointMessage(
      checkpointRequest: CheckpointRequest
  ): IO[Boolean] = ???

  override def insertPrepareMessage(prepare: PrepareRequest): IO[Boolean] =
    ???

  override def insertCommitMessage(commit: CommitRequest): IO[Boolean] = ???

  override def insertNewSession(
      sessionId: String,
      sessionInfo: SessionInfo
  ): IO[Unit] = ???

  override def getSession(sessionId: String): IO[Option[SessionInfo]] = ???

  override def updateSession(
      sessionId: String,
      sessionInfo: SessionInfo
  ): IO[Unit] = ???

  override def insertBlockchainEvent(event: BlockchainEvent): IO[Unit] = ???

  override def initializeStorage(): IO[Unit] = ???

}
