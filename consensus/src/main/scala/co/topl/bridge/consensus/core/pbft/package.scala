package co.topl.bridge.consensus.core

import cats.effect.kernel.Sync
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.shared.ReplicaCount
import java.security.MessageDigest

package object pbft {

  def createStateDigestAux(
      state: Map[String, PBFTState]
  ) = {
    val stateBytes = state.toList
      .sortBy(_._1)
      .map(x => x._1.getBytes ++ x._2.toBytes)
      .flatten
    MessageDigest
      .getInstance("SHA-256")
      .digest(stateBytes.toArray)
  }

  def createStateDigest(
      state: SessionState
  ) = {
    // import JavaConverters
    import scala.jdk.CollectionConverters._
    val stateBytes = createStateDigestAux(
      state.underlying.entrySet.asScala.toList
        .map(x => x.getKey -> x.getValue)
        .toMap
    )
    MessageDigest
      .getInstance("SHA-256")
      .digest(stateBytes.toArray)
  }

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
