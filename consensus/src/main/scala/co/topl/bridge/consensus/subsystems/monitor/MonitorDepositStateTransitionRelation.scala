package co.topl.bridge.consensus.subsystems.monitor
import co.topl.bridge.consensus.core.BTCWaitExpirationTime
import co.topl.bridge.consensus.subsystems.monitor.EndTransition
import co.topl.bridge.consensus.subsystems.monitor.FSMTransition
import co.topl.bridge.consensus.subsystems.monitor.FSMTransitionTo
import co.topl.bridge.consensus.subsystems.monitor.MConfirmingBTCDeposit
import co.topl.bridge.consensus.subsystems.monitor.MWaitingForBTCDeposit
import co.topl.bridge.consensus.subsystems.monitor.PeginStateMachineState
import org.bitcoins.core.protocol.Bech32Address
import co.topl.bridge.consensus.core.BTCConfirmationThreshold

trait MonitorDepositStateTransitionRelation extends TransitionToEffect {

  def handleBlockchainEventDeposit[F[_]](
      currentState: DepositState,
      blockchainEvent: BlockchainEvent
  )(
      t2E: (PeginStateMachineState, BlockchainEvent) => F[Unit]
  )(implicit
      btcWaitExpirationTime: BTCWaitExpirationTime,
      btcConfirmationThreshold: BTCConfirmationThreshold
  ): Option[FSMTransition] =
    ((currentState, blockchainEvent) match {
      case (
            cs: MWaitingForBTCDeposit,
            ev: BTCFundsDeposited
          ) =>
        val bech32Address = Bech32Address.fromString(cs.escrowAddress)
        if (ev.scriptPubKey == bech32Address.scriptPubKey.asmHex) {
          Some(
            FSMTransitionTo(
              currentState,
              MConfirmingBTCDeposit(
                cs.currentBTCBlockHeight,
                ev.fundsDepositedHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.escrowAddress,
                cs.redeemAddress,
                cs.claimAddress,
                ev.txId,
                ev.vout,
                ev.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else
          None
      case (
            cs: MWaitingForBTCDeposit,
            ev: NewBTCBlock
          ) =>
        if (
          btcWaitExpirationTime.underlying < (ev.height - cs.currentBTCBlockHeight)
        )
          Some(
            EndTransition[F](
              t2E(currentState, blockchainEvent)
            )
          )
        else
          None
      case (
            cs: MConfirmingBTCDeposit,
            ev: NewBTCBlock
          ) =>
        // check that the confirmation threshold has been passed
        if (
          isAboveConfirmationThresholdBTC(ev.height, cs.depositBTCBlockHeight)
        )
          Some(
            FSMTransitionTo(
              currentState,
              MMintingTBTC(
                cs.startBTCBlockHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                cs.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        else if (ev.height <= cs.depositBTCBlockHeight) {
          // we are seeing the block where the transaction was found again
          // this can only mean that block is being unapplied
          Some(
            FSMTransitionTo(
              currentState,
              MWaitingForBTCDeposit(
                cs.startBTCBlockHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.escrowAddress,
                cs.redeemAddress,
                cs.claimAddress
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else None
      case (
            _: MConfirmingBTCDeposit,
            _
          ) =>
        None // No transition
      case (
            _: MWaitingForBTCDeposit,
            _
          ) =>
        None // No transition
    })
}
