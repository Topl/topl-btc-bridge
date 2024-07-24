package co.topl.bridge

package object publicapi {

  class ClientNumber(val value: Int) extends AnyVal

  class ReplicaCount(val value: Int) extends AnyVal {

    def maxFailures = (value - 1) / 3
  }

  case class ConsensusClientMessageId(
      timestamp: Long
  )

  case class ReplicaNode[F[_]](
      id: Int,
      backendHost: String,
      backendPort: Int,
      backendSecure: Boolean
  )

}
