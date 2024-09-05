package co.topl.bridge.consensus.subsystems.monitor

import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.brambl.utils.Encoding
import co.topl.bridge.consensus.shared.AssetToken
import co.topl.bridge.consensus.shared.BTCWaitExpirationTime
import co.topl.bridge.consensus.shared.ToplConfirmationThreshold
import co.topl.bridge.consensus.shared.ToplWaitExpirationTime
import co.topl.bridge.consensus.subsystems.monitor.EndTransition
import co.topl.bridge.consensus.subsystems.monitor.FSMTransition
import co.topl.bridge.consensus.subsystems.monitor.FSMTransitionTo
import co.topl.bridge.consensus.subsystems.monitor.MConfirmingTBTCMint
import co.topl.bridge.consensus.subsystems.monitor.MMintingTBTC
import co.topl.bridge.consensus.subsystems.monitor.MWaitingForClaim
import co.topl.bridge.consensus.subsystems.monitor.MWaitingForRedemption
import co.topl.bridge.consensus.subsystems.monitor.PeginStateMachineState

trait MonitorMintingStateTransitionRelation extends TransitionToEffect {

  def handleBlockchainEventMinting[F[_]](
      currentState: MintingState,
      blockchainEvent: BlockchainEvent
  )(
      t2E: (PeginStateMachineState, BlockchainEvent) => F[Unit]
  )(implicit
      btcWaitExpirationTime: BTCWaitExpirationTime,
      toplWaitExpirationTime: ToplWaitExpirationTime,
      toplConfirmationThreshold: ToplConfirmationThreshold,
      groupId: GroupId,
      seriesId: SeriesId
  ): Option[FSMTransition] =
    ((currentState, blockchainEvent) match {
      case (
            cs: MMintingTBTC,
            ev: NewBTCBlock
          ) =>
        if (
          ev.height - cs.startBTCBlockHeight > btcWaitExpirationTime.underlying
        )
          Some(
            EndTransition[F](
              t2E(currentState, blockchainEvent)
            )
          )
        else
          None
      case (
            cs: MMintingTBTC,
            be: BifrostFundsDeposited
          ) =>
        import co.topl.brambl.syntax._

        if (
          cs.redeemAddress == be.address &&
          AssetToken(
            Encoding.encodeToBase58(groupId.value.toByteArray),
            Encoding.encodeToBase58(seriesId.value.toByteArray),
            cs.amount.satoshis.toBigInt
          ) == be.amount
        ) {
          Some(
            FSMTransitionTo(
              currentState,
              MConfirmingTBTCMint(
                cs.startBTCBlockHeight,
                be.currentToplBlockHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                be.utxoTxId,
                be.utxoIndex,
                cs.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else None
      case (
            cs: MConfirmingTBTCMint,
            ev: NewBTCBlock
          ) =>
        if (
          ev.height - cs.startBTCBlockHeight > btcWaitExpirationTime.underlying
        )
          Some(
            EndTransition[F](
              t2E(currentState, blockchainEvent)
            )
          )
        else None
      case (
            cs: MConfirmingTBTCMint,
            be: NewToplBlock
          ) =>
        if (
          isAboveConfirmationThresholdTopl(be.height, cs.depositTBTCBlockHeight)
        ) {
          import co.topl.brambl.syntax._
          Some(
            FSMTransitionTo(
              currentState,
              MWaitingForRedemption(
                cs.depositTBTCBlockHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                cs.utxoTxId,
                cs.utxoIndex,
                AssetToken(
                  Encoding.encodeToBase58(groupId.value.toByteArray),
                  Encoding.encodeToBase58(seriesId.value.toByteArray),
                  cs.amount.satoshis.toBigInt
                )
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else if (be.height <= cs.depositTBTCBlockHeight) {
          // we are seeing the block where the transaction was found again
          // this can only mean that block is being unapplied
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

        } else
          None
      case (
            cs: MWaitingForRedemption,
            ev: NewToplBlock
          ) =>
        if (
          toplWaitExpirationTime.underlying < (ev.height - cs.currentTolpBlockHeight)
        )
          Some(
            EndTransition[F](
              t2E(currentState, blockchainEvent)
            )
          )
        else
          None
      case (
            cs: MWaitingForRedemption,
            be: BifrostFundsWithdrawn
          ) =>
        if (cs.utxoTxId == be.txId && cs.utxoIndex == be.txIndex) {
          Some(
            FSMTransitionTo(
              currentState,
              MConfirmingRedemption(
                None,
                None,
                be.secret,
                be.fundsWithdrawnHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                cs.utxoTxId,
                cs.utxoIndex,
                cs.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else None
      case (
            cs: MConfirmingRedemption,
            ev: NewToplBlock
          ) =>
        if (
          isAboveConfirmationThresholdTopl(ev.height, cs.currentTolpBlockHeight)
        )
          Some(
            FSMTransitionTo(
              currentState,
              MWaitingForClaim(
                None,
                cs.secret,
                cs.currentWalletIdx,
                cs.btcTxId,
                cs.btcVout,
                cs.scriptAsm,
                cs.amount,
                cs.claimAddress
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        else if (ev.height <= cs.currentTolpBlockHeight) {
          import cats.implicits._
          import co.topl.brambl.syntax._
          import org.bitcoins.core.currency.Satoshis
          // we are seeing the block where the transaction was found again
          // this can only mean that block is being unapplied
          (cs.startBTCBlockHeight, cs.depositBTCBlockHeight)
            .mapN((startBTCBlockHeight, depositBTCBlockHeight) =>
              FSMTransitionTo(
                currentState,
                MConfirmingTBTCMint(
                  startBTCBlockHeight,
                  depositBTCBlockHeight,
                  cs.currentWalletIdx,
                  cs.scriptAsm,
                  cs.redeemAddress,
                  cs.claimAddress,
                  cs.btcTxId,
                  cs.btcVout,
                  cs.utxoTxId,
                  cs.utxoIndex,
                  Satoshis(int128AsBigInt(cs.amount.amount))
                ),
                t2E(currentState, blockchainEvent)
              )
            )
            .orElse(
              Some(
                FSMTransitionTo(
                  currentState,
                  MWaitingForRedemption(
                    cs.currentTolpBlockHeight,
                    cs.currentWalletIdx,
                    cs.scriptAsm,
                    cs.redeemAddress,
                    cs.claimAddress,
                    cs.btcTxId,
                    cs.btcVout,
                    cs.utxoTxId,
                    cs.utxoIndex,
                    cs.amount
                  ),
                  t2E(currentState, blockchainEvent)
                )
              )
            )
        } else
          None
      case (
            cs: MConfirmingTBTCMint,
            be: BifrostFundsWithdrawn
          ) =>
        if (cs.utxoTxId == be.txId && cs.utxoIndex == be.txIndex) {
          Some(
            FSMTransitionTo(
              currentState,
              MConfirmingRedemption(
                Some(cs.startBTCBlockHeight),
                Some(cs.depositTBTCBlockHeight),
                be.secret,
                be.fundsWithdrawnHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                cs.utxoTxId,
                cs.utxoIndex,
                be.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        } else None
      case (
            _: MMintingTBTC,
            _
          ) =>
        None // No transition
      case (
            _: MConfirmingTBTCMint,
            _
          ) =>
        None // No transition
      case (
            _: MWaitingForRedemption,
            _
          ) =>
        None // No transition

      case (
            _: MConfirmingRedemption,
            _
          ) =>
        None // No transition

    })
}
