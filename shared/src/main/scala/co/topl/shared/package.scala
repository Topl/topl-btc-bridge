package co.topl

import co.topl.bridge.consensus.service.StateMachineRequest
import co.topl.bridge.consensus.service.StateMachineReply

package object shared {
    object implicits {

    // add extension method to StateMachineRequest
    implicit class StateMachineRequestOp(val request: StateMachineRequest)
        extends AnyVal {
      def signableBytes: Array[Byte] = {
        BigInt(request.timestamp).toByteArray ++
          BigInt(request.clientNumber).toByteArray ++
          request.operation.startSession
            .map(x => x.pkey.getBytes() ++ x.sha256.getBytes())
            .getOrElse(Array.emptyByteArray) ++
          request.operation.mintingStatus
            .map(_.sessionId.getBytes())
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
            .getOrElse(Array.emptyByteArray) ++
          reply.result.mintingStatus
            .map(_.sessionId.getBytes())
            .getOrElse(Array.emptyByteArray)
      }
    }

  }
}
