package co.topl.bridge.consensus.subsystems.monitor

import cats.effect.kernel.Async
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.bridge.consensus.core.BTCConfirmationThreshold
import co.topl.bridge.consensus.core.BTCRetryThreshold
import co.topl.bridge.consensus.core.BTCWaitExpirationTime
import co.topl.bridge.consensus.core.ToplConfirmationThreshold
import co.topl.bridge.consensus.core.ToplWaitExpirationTime
import co.topl.bridge.consensus.subsystems.monitor.FSMTransition
import co.topl.bridge.consensus.subsystems.monitor.PeginStateMachineState
import org.typelevel.log4cats.Logger

object MonitorTransitionRelation
    extends TransitionToEffect
    with MonitorDepositStateTransitionRelation
    with MonitorClaimStateTransitionRelation
    with MonitorMintingStateTransitionRelation {

  def handleBlockchainEvent[F[_]: Async: Logger](
      currentState: PeginStateMachineState,
      blockchainEvent: BlockchainEvent
  )(
      t2E: (PeginStateMachineState, BlockchainEvent) => F[Unit]
  )(implicit
      btcRetryThreshold: BTCRetryThreshold,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcConfirmationThreshold: BTCConfirmationThreshold,
      toplConfirmationThreshold: ToplConfirmationThreshold,
      groupId: GroupId,
      seriesId: SeriesId
  ): Option[FSMTransition] =
    ((currentState, blockchainEvent) match {
      case (
            cs: DepositState,
            ev: BlockchainEvent
          ) =>
        handleBlockchainEventDeposit(cs, ev)(t2E)
      case (
            cs: ClaimState,
            ev: BlockchainEvent
          ) =>
        handleBlockchainEventClaim(cs, ev)(t2E)
      case (
            cs: MintingState,
            ev: BlockchainEvent
          ) =>
        handleBlockchainEventMinting(cs, ev)(t2E)
    })
}
