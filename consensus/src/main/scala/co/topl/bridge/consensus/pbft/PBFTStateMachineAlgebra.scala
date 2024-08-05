package co.topl.bridge.consensus.pbft

import cats.effect.kernel.Async
import java.util.concurrent.ConcurrentHashMap

trait PBFTStateMachineAlgebra[F[_]] {

  def handleBlockchainEventInContext(
      pbftEvent: PBFTEvent
  ): F[Unit]

}

object PBFTStateMachineAlgebra {

  def make[F[_]: Async](
      map: ConcurrentHashMap[String, PBFTState]
  ): PBFTStateMachineAlgebra[F] =
    new PBFTStateMachineAlgebra[F] {
      import cats.implicits._
      override def handleBlockchainEventInContext(
          pbftEvent: PBFTEvent
      ): F[Unit] = for {
        entry <- Async[F].delay(map.get(pbftEvent.sessionId))
        sessionId = pbftEvent.sessionId
      } yield ()
    }

}
