package co.topl.bridge.consensus.subsystems.monitor

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.bridge.consensus.shared.PeginSessionInfo
import co.topl.bridge.consensus.shared.BTCConfirmationThreshold
import co.topl.bridge.consensus.shared.BTCRetryThreshold
import co.topl.bridge.consensus.shared.BTCWaitExpirationTime
import co.topl.bridge.consensus.shared.ToplConfirmationThreshold
import co.topl.bridge.consensus.shared.ToplWaitExpirationTime
import co.topl.bridge.consensus.subsystems.monitor.EndTransition
import co.topl.bridge.consensus.subsystems.monitor.FSMTransitionTo
import co.topl.bridge.consensus.subsystems.monitor.MWaitingForBTCDeposit
import co.topl.bridge.consensus.subsystems.monitor.MonitorTransitionRelation
import co.topl.bridge.consensus.subsystems.monitor.PeginStateMachineState
import co.topl.bridge.shared.ClientId
import co.topl.bridge.shared.StateMachineServiceGrpcClient
import co.topl.bridge.shared.SessionId
import org.typelevel.log4cats.Logger

import java.util.Map.Entry
import java.util.concurrent.ConcurrentHashMap

trait MonitorStateMachineAlgebra[F[_]] {

  def handleBlockchainEventInContext(
      blockchainEvent: BlockchainEvent
  ): fs2.Stream[F, F[Unit]]

  def innerStateConfigurer(
      sessionEvent: SessionEvent
  ): F[Unit]

}

object MonitorStateMachine {

  def make[F[_]: Async: Logger](
      currentBitcoinNetworkHeight: Ref[F, Int],
      currentToplNetworkHeight: Ref[F, Long],
      map: ConcurrentHashMap[String, PeginStateMachineState]
  )(implicit
      clientId: ClientId,
      consensusClient: StateMachineServiceGrpcClient[F],
      btcWaitExpirationTime: BTCWaitExpirationTime,
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcRetryThreshold: BTCRetryThreshold,
      btcConfirmationThreshold: BTCConfirmationThreshold,
      toplConfirmationThreshold: ToplConfirmationThreshold,
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId
  ) = new MonitorStateMachineAlgebra[F] {

    import org.typelevel.log4cats.syntax._
    import MonitorTransitionRelation._

    private def updateBTCHeight(
        blockchainEvent: BlockchainEvent
    ): fs2.Stream[F, F[Unit]] =
      blockchainEvent match {
        case NewBTCBlock(height) =>
          fs2.Stream(
            for {
              x <- currentBitcoinNetworkHeight.get
              _ <-
                if (height > x)
                  currentBitcoinNetworkHeight.set(height)
                else Sync[F].unit
            } yield ()
          )

        case _ => fs2.Stream.empty
      }

    private def updateToplHeight(
        blockchainEvent: BlockchainEvent
    ): fs2.Stream[F, F[Unit]] =
      blockchainEvent match {
        case NewToplBlock(height) =>
          fs2.Stream(
            for {
              x <- currentToplNetworkHeight.get
              _ <- trace"current topl height is $x"
              _ <- trace"Updating topl height to $height"
              _ <-
                if (height > x)
                  currentToplNetworkHeight.set(height)
                else Sync[F].unit
            } yield ()
          )

        case _ => fs2.Stream.empty
      }

    def handleBlockchainEventInContext(
        blockchainEvent: BlockchainEvent
    ): fs2.Stream[F, F[Unit]] = {
      import scala.jdk.CollectionConverters._
      updateToplHeight(blockchainEvent) ++
        updateBTCHeight(blockchainEvent) ++ (for {
          entry <- fs2.Stream[F, Entry[String, PeginStateMachineState]](
            map.entrySet().asScala.toList: _*
          )
          sessionId = entry.getKey
          currentState = entry.getValue
        } yield {
          implicit val sessionIdImplicit = new SessionId(sessionId)
          handleBlockchainEvent[F](
            currentState,
            blockchainEvent
          )(transitionToEffect[F])
            .orElse(
              Some(
                FSMTransitionTo(
                  currentState,
                  currentState,
                  transitionToEffect(currentState, blockchainEvent)
                )
              )
            )
        }
          .map(x =>
            x match {
              case EndTransition(effect) =>
                info"Session $sessionId ended successfully" >>
                  Sync[F].delay(map.remove(sessionId)) >>
                  Sync[F]
                    .delay(
                      map
                        .remove(sessionId)
                    )
                    .flatMap(_ => effect.asInstanceOf[F[Unit]])
              case FSMTransitionTo(prevState, nextState, effect)
                  if (prevState != nextState) =>
                info"Transitioning session $sessionId from ${currentState
                    .getClass()
                    .getSimpleName()} to ${nextState.getClass().getSimpleName()}" >>
                  processTransition(
                    sessionId,
                    FSMTransitionTo[F](
                      prevState,
                      nextState,
                      effect.asInstanceOf[F[Unit]]
                    )
                  )
              case FSMTransitionTo(_, _, _) =>
                Sync[F].unit
            }
          )).collect { case Some(value) =>
          if (blockchainEvent.isInstanceOf[NewBTCBlock])
            debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[NewBTCBlock].height}" >> value
          else if (blockchainEvent.isInstanceOf[SkippedBTCBlock])
            debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[SkippedBTCBlock].height}" >> value
          else if (blockchainEvent.isInstanceOf[NewToplBlock])
            debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[NewToplBlock].height}" >> value
          else
            debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()}" >> value
        }
    }

    def processTransition(sessionId: String, transition: FSMTransitionTo[F]) =
      Sync[F].delay(map.replace(sessionId, transition.nextState)) >>
        transition.effect

    def innerStateConfigurer(
        sessionEvent: SessionEvent
    ): F[Unit] = {
      sessionEvent match {
        case SessionCreated(sessionId, psi: PeginSessionInfo) =>
          info"New session created, waiting for funds at ${psi.escrowAddress}" >>
            currentBitcoinNetworkHeight.get.flatMap(cHeight =>
              Sync[F].delay(
                map.put(
                  sessionId,
                  MWaitingForBTCDeposit(
                    cHeight,
                    psi.btcPeginCurrentWalletIdx,
                    psi.scriptAsm,
                    psi.escrowAddress,
                    psi.redeemAddress,
                    psi.claimAddress
                  )
                )
              )
            )
        case SessionUpdated(_, _: PeginSessionInfo) =>
          Sync[F].unit
      }
    }

  }
}
