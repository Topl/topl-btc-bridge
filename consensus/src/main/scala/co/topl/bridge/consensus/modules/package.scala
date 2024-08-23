package co.topl.bridge.consensus

import cats.effect.kernel.Sync
import co.topl.bridge.consensus.persistence.StorageApi
import co.topl.shared.ReplicaCount

package object modules {

  def isPrepared[F[_]: Sync](
      viewNumber: Long,
      sequenceNumber: Long
  )(implicit replicaCount: ReplicaCount, storageApi: StorageApi[F]) = {
    import cats.implicits._
    for {
      somePrePrepareMessage <- storageApi.getPrePrepareMessage(
        viewNumber,
        sequenceNumber
      )
      prepareMessages <- storageApi.getPrepareMessages(
        viewNumber,
        sequenceNumber
      )
    } yield somePrePrepareMessage.isDefined &&
      somePrePrepareMessage
        .map(prePrepareMessage =>
          (prepareMessages
            .filter(x =>
              x.digest.toByteArray
                .sameElements(prePrepareMessage.digest.toByteArray())
            )
            .size > replicaCount.maxFailures)
        )
        .getOrElse(false)
  }

  def isCommitted[F[_]: Sync](
      viewNumber: Long,
      sequenceNumber: Long
  )(implicit replicaCount: ReplicaCount, storageApi: StorageApi[F]) = {
    import cats.implicits._
    for {
      somePrePrepareMessage <- storageApi.getPrePrepareMessage(
        viewNumber,
        sequenceNumber
      )
      commitMessages <- storageApi.getCommitMessages(
        viewNumber,
        sequenceNumber
      )
    } yield somePrePrepareMessage.isDefined &&
      somePrePrepareMessage
        .map(prePrepareMessage =>
          (commitMessages
            .filter(x =>
              x.digest.toByteArray
                .sameElements(prePrepareMessage.digest.toByteArray())
            )
            .size > replicaCount.maxFailures)
        )
        .getOrElse(false)
  }

}
