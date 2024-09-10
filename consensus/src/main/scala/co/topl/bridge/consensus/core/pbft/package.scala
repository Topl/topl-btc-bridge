package co.topl.bridge.consensus.core

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.shared.BridgeCryptoUtils
import co.topl.bridge.shared.ReplicaCount

import java.security.MessageDigest
import java.security.PublicKey

package object pbft {

  def checkMessageSignaturePrimary[F[_]: Async](
      replicaKeysMap: Map[Int, PublicKey],
      requestSignableBytes: Array[Byte],
      requestSignature: Array[Byte]
  )(implicit
      currentViewRef: CurrentViewRef[F],
      replicaCount: ReplicaCount
  ): F[Boolean] = {
    import cats.implicits._
    for {
      currentView <- currentViewRef.underlying.get
      currentPrimary = (currentView % replicaCount.value).toInt
      publicKey = replicaKeysMap(currentPrimary)
      isValidSignature <- BridgeCryptoUtils.verifyBytes[F](
        publicKey,
        requestSignableBytes,
        requestSignature
      )
    } yield isValidSignature
  }

  def checkMessageSignature[F[_]: Async](
      replicaId: Int,
      replicaKeysMap: Map[Int, PublicKey],
      requestSignableBytes: Array[Byte],
      requestSignature: Array[Byte]
  ): F[Boolean] = {
    import cats.implicits._
    val publicKey = replicaKeysMap(replicaId)
    for {
      isValidSignature <- BridgeCryptoUtils.verifyBytes[F](
        publicKey,
        requestSignableBytes,
        requestSignature
      )
    } yield isValidSignature
  }


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
