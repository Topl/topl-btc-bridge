package co.topl

import co.topl.bridge.shared.StateMachineRequest
import co.topl.bridge.consensus.service.StateMachineReply
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.CheckpointRequest

package object shared {

  
  case class ReplicaId(
      id: Int
  )

  case class SessionId(
      id: String
  )

  case class ClientId(
      id: Int
  )

  case class ConsensusClientMessageId(
      timestamp: Long
  )

  class ReplicaCount(val value: Int) extends AnyVal {

    def maxFailures = (value - 1) / 3
  }

  class ClientCount(val value: Int) extends AnyVal

  case class ReplicaNode[F[_]](
      id: Int,
      backendHost: String,
      backendPort: Int,
      backendSecure: Boolean
  )

  object implicits {

    implicit class CheckpointRequestOp(val request: CheckpointRequest) {
      def signableBytes: Array[Byte] = {
        BigInt(request.sequenceNumber).toByteArray ++
          request.digest.toByteArray() ++
          BigInt(request.replicaId).toByteArray
      }
    }

    implicit class PrePrepareRequestOp(val request: PrePrepareRequest) {
      def signableBytes: Array[Byte] = {
        BigInt(request.viewNumber).toByteArray ++
          BigInt(request.sequenceNumber).toByteArray ++
          request.digest.toByteArray()
      }
    }

    implicit class CommitRequestOp(val request: CommitRequest) {
      def signableBytes: Array[Byte] = {
        BigInt(request.viewNumber).toByteArray ++
          BigInt(request.sequenceNumber).toByteArray ++
          request.digest.toByteArray() ++
          BigInt(request.replicaId).toByteArray
      }
    }

    implicit class PrepareRequestOp(val request: PrepareRequest) {
      def signableBytes: Array[Byte] = {
        BigInt(request.viewNumber).toByteArray ++
          BigInt(request.sequenceNumber).toByteArray ++
          request.digest.toByteArray() ++
          BigInt(request.replicaId).toByteArray
      }
    }

    // add extension method to StateMachineRequest
    implicit class StateMachineRequestOp(val request: StateMachineRequest)
        extends AnyVal {
      def signableBytes: Array[Byte] = {
        BigInt(request.timestamp).toByteArray ++
          BigInt(request.clientNumber).toByteArray ++
          request.operation.startSession
            .map(x => x.pkey.getBytes() ++ x.sha256.getBytes())
            .getOrElse(Array.emptyByteArray)
      }
    }

    // add extension method to StateMachineReply
    implicit class StateMachineReplyOp(val reply: StateMachineReply)
        extends AnyVal {
      def signableBytes: Array[Byte] = {
        BigInt(reply.viewNumber).toByteArray ++
          BigInt(reply.timestamp).toByteArray ++
          BigInt(reply.replicaNumber).toByteArray ++
          reply.result.startSession
            .map(x =>
              x.sessionId.getBytes() ++ x.script.getBytes() ++ x.escrowAddress
                .getBytes() ++ x.descriptor.getBytes() ++ BigInt(
                x.minHeight
              ).toByteArray ++ BigInt(x.maxHeight).toByteArray
            )
            .getOrElse(Array.emptyByteArray)
      }
    }

  }
}
