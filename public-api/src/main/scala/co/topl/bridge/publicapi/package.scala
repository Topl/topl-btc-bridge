package co.topl.bridge

import co.topl.bridge.consensus.service.servces.StateMachineRequest

package object publicapi {
  class ClientNumber(val value: Int) extends AnyVal

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

  }

}
