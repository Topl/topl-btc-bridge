package co.topl.bridge.consensus.core.pbft
import cats.effect.kernel.Async
import cats.implicits._
import co.topl.brambl.utils.Encoding
import co.topl.bridge.consensus.core.KWatermark
import co.topl.bridge.consensus.core.StableCheckpointRef
import co.topl.bridge.consensus.core.StateSnapshotRef
import co.topl.bridge.consensus.core.UnstableCheckpointsRef
import co.topl.bridge.consensus.core.WatermarkRef
import co.topl.bridge.consensus.core.pbft.PBFTState
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.shared.Empty
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.implicits._
import org.typelevel.log4cats.Logger

import java.security.PublicKey

object CheckpointActivity {

  private sealed trait CheckpointProblem extends Throwable
  private case object InvalidSignature extends CheckpointProblem
  private case object MessageTooOld extends CheckpointProblem
  private case object LogAlreadyExists extends CheckpointProblem

  private def checkLowWatermark[F[_]: Async](request: CheckpointRequest)(
      implicit lastStableCheckpointRef: StableCheckpointRef[F]
  ) = {
    for {
      lastStableCheckpoint <- lastStableCheckpointRef.underlying.get
      _ <-
        if (request.sequenceNumber < lastStableCheckpoint.sequenceNumber)
          Async[F].raiseError(MessageTooOld)
        else Async[F].unit
    } yield ()

  }

  private def checkSignature[F[_]: Async](
      replicaKeysMap: Map[Int, PublicKey],
      request: CheckpointRequest
  ): F[Unit] = {
    for {
      reqSignCheck <- checkMessageSignature(
        request.replicaId,
        replicaKeysMap,
        request.signableBytes,
        request.signature.toByteArray()
      )
      _ <-
        Async[F].raiseUnless(reqSignCheck)(
          InvalidSignature
        )
    } yield ()
  }

  private def checkExistingLog[F[_]: Async](
      request: CheckpointRequest
  )(implicit storageApi: StorageApi[F]): F[Unit] = {
    for {
      someCheckpointMessage <- storageApi
        .getCheckpointMessage(request.sequenceNumber, request.replicaId)
      _ <- Async[F]
        .raiseWhen(someCheckpointMessage.isDefined)(LogAlreadyExists)
    } yield ()
  }

  private def handleUnstableCheckpoint[F[_]: Async](
      request: CheckpointRequest
  )(implicit
      replicaCount: ReplicaCount,
      latestStateSnapshotRef: StateSnapshotRef[F],
      unstableCheckpointsRef: UnstableCheckpointsRef[F]
  ): F[(Boolean, Map[Int, CheckpointRequest], Map[String, PBFTState])] = {
    for {
      unstableCheckpoints <- unstableCheckpointsRef.underlying.get
      someCheckpointVotes = unstableCheckpoints.get(
        request.sequenceNumber -> Encoding.encodeToHex(
          request.digest.toByteArray()
        )
      )
      newVotes <- someCheckpointVotes match {
        case None =>
          unstableCheckpointsRef.underlying.set(
            unstableCheckpoints + ((request.sequenceNumber -> Encoding
              .encodeToHex(
                request.digest.toByteArray()
              )) -> Map(request.replicaId -> request))
          ) >>
            Map(
              request.replicaId -> request
            ).pure[F]
        case Some(checkpointVotes) =>
          unstableCheckpointsRef.underlying.set(
            unstableCheckpoints + ((request.sequenceNumber -> Encoding
              .encodeToHex(
                request.digest.toByteArray()
              )) -> (checkpointVotes + (request.replicaId -> request)))
          ) >>
            (checkpointVotes + (request.replicaId -> request)).pure[F]
      }
      seqAndState <- latestStateSnapshotRef.state.get
      (sequenceNumber, stateSnapshotDigest, state) = seqAndState
    } yield {
      val haveNewStableState = newVotes.size > replicaCount.maxFailures &&
        sequenceNumber == request.sequenceNumber &&
        stateSnapshotDigest == Encoding.encodeToHex(
          request.digest.toByteArray()
        )
      (haveNewStableState, newVotes, state)
    }
  }

  private def handleNewStableCheckpoint[F[_]: Async](
      request: CheckpointRequest,
      certificates: Map[Int, CheckpointRequest],
      state: Map[String, PBFTState]
  )(implicit
      storageApi: StorageApi[F],
      unstableCheckpointsRef: UnstableCheckpointsRef[F],
      kWatermark: KWatermark,
      watermarkRef: WatermarkRef[F],
      lastStableCheckpointRef: StableCheckpointRef[F]
  ): F[Unit] = {
    for {
      _ <- lastStableCheckpointRef.underlying.update(x =>
        x.copy(
          sequenceNumber = request.sequenceNumber,
          certificates = certificates,
          state = state
        )
      )
      lowAndHigh <- watermarkRef.lowAndHigh.get
      (lowWatermark, highWatermark) = lowAndHigh
      _ <- watermarkRef.lowAndHigh.set(
        (
          request.sequenceNumber,
          request.sequenceNumber + kWatermark.underlying
        )
      )
      _ <- unstableCheckpointsRef.underlying.update(x =>
        x.filter(_._1._1 < request.sequenceNumber)
      )
      _ <- storageApi.cleanLog(request.sequenceNumber)
    } yield ()
  }

  private def checkIfStable[F[_]: Async](
      request: CheckpointRequest
  )(implicit lastStableCheckpointRef: StableCheckpointRef[F]) = {
    import cats.implicits._
    for {
      lastStableCheckpoint <- lastStableCheckpointRef.underlying.get
    } yield (
      lastStableCheckpoint,
      lastStableCheckpoint.sequenceNumber == request.sequenceNumber &&
        Encoding.encodeToHex(
          createStateDigestAux(lastStableCheckpoint.state)
        ) == Encoding.encodeToHex(request.digest.toByteArray())
    )
  }

  private def performCheckpoint[F[_]: Async](
      request: CheckpointRequest
  )(implicit
      watermarkRef: WatermarkRef[F],
      kWatermark: KWatermark,
      replicaCount: ReplicaCount,
      storageApi: StorageApi[F],
      latestStateSnapshotRef: StateSnapshotRef[F],
      unstableCheckpointsRef: UnstableCheckpointsRef[F],
      lastStableCheckpointRef: StableCheckpointRef[F]
  ): F[Unit] = {
    for {
      _ <- storageApi.insertCheckpointMessage(request)
      lastStableCheckpointAndisStable <- checkIfStable(request)
      (lastStableCheckpoint, isStable) = lastStableCheckpointAndisStable
      _ <-
        if (isStable)
          lastStableCheckpointRef.underlying.set(
            lastStableCheckpoint.copy(
              certificates = lastStableCheckpoint.certificates + (
                request.replicaId -> request
              )
            )
          )
        else {
          for {
            triplet <- handleUnstableCheckpoint(request)
            (haveNewStableState, certificates, state) = triplet
            _ <-
              if (haveNewStableState) {
                for {
                  _ <- handleNewStableCheckpoint(
                    request,
                    certificates,
                    state
                  )
                } yield ()
              } else Async[F].unit
          } yield ()
        }
    } yield ()
  }

  def performValidation[F[_]: Async](
      replicaKeysMap: Map[Int, PublicKey],
      request: CheckpointRequest
  )(implicit
      storageApi: StorageApi[F],
      lastStableCheckpointRef: StableCheckpointRef[F]
  ): F[Unit] = {
    for {
      _ <- checkSignature(replicaKeysMap, request)
      _ <- checkLowWatermark(request)
      _ <- checkExistingLog(request)
    } yield ()
  }


  def apply[F[_]: Async: Logger](
      replicaKeysMap: Map[Int, PublicKey],
      request: CheckpointRequest
  )(implicit
      watermarkRef: WatermarkRef[F],
      kWatermark: KWatermark,
      replicaCount: ReplicaCount,
      storageApi: StorageApi[F],
      latestStateSnapshotRef: StateSnapshotRef[F],
      unstableCheckpointsRef: UnstableCheckpointsRef[F],
      lastStableCheckpointRef: StableCheckpointRef[F]
  ): F[Empty] = {
    import org.typelevel.log4cats.syntax._
    (for {
      _ <- performValidation(replicaKeysMap, request)
      _ <- performCheckpoint(request)
    } yield Empty()).handleErrorWith(e =>
      (e match {
        case InvalidSignature =>
          error"Error handling checkpoint request: Signature verification failed"
        case MessageTooOld =>
          warn"Error handling checkpoint request: Checkpoint message is older than last stable checkpoint"
        case LogAlreadyExists =>
          warn"Error handling checkpoint request: The log is already present"
        case e =>
          error"Error handling checkpoint request: ${e.getMessage()}"
      }) >>
        Async[F].pure(Empty())
    )

  }

}
