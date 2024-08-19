package co.topl.bridge.consensus.monitor

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.brambl.utils.Encoding
import co.topl.bridge.consensus.AssetToken
import co.topl.bridge.consensus.BTCConfirmationThreshold
import co.topl.bridge.consensus.BTCRetryThreshold
import co.topl.bridge.consensus.BTCWaitExpirationTime
import co.topl.bridge.consensus.ToplConfirmationThreshold
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.monitor.EndTransition
import co.topl.bridge.consensus.monitor.FSMTransition
import co.topl.bridge.consensus.monitor.FSMTransitionTo
import co.topl.bridge.consensus.monitor.MConfirmingBTCDeposit
import co.topl.bridge.consensus.monitor.MMintingTBTC
import co.topl.bridge.consensus.monitor.MWaitingForBTCDeposit
import co.topl.bridge.consensus.monitor.MintingTBTCConfirmation
import co.topl.bridge.consensus.monitor.PeginStateMachineState
import co.topl.bridge.consensus.monitor.WaitingForClaim
import co.topl.bridge.consensus.monitor.WaitingForClaimBTCConfirmation
import co.topl.bridge.consensus.monitor.WaitingForRedemption
import org.bitcoins.core.protocol.Bech32Address
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

object MonitorTransitionRelation extends TransitionToEffect{


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
            cs: WaitingForRedemption,
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
            cs: MConfirmingBTCDeposit,
            ev: NewBTCBlock
          ) =>
        // check that the confirmation threshold has been passed
        if (isAboveConfirmationThresholdBTC(ev.height, cs.depositBTCBlockHeight))
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
            cs: WaitingForClaimBTCConfirmation,
            ev: NewBTCBlock
          ) =>
        // check that the confirmation threshold has been passed
        if (isAboveConfirmationThresholdBTC(ev.height, cs.claimBTCBlockHeight))
          // we have successfully claimed the BTC
          Some(
            EndTransition[F](
              info"Successfully confirmed claim transaction" >> t2E(
                currentState,
                blockchainEvent
              )
            )
          )
        else if (ev.height <= cs.claimBTCBlockHeight)
          // we are seeing the block where the transaction was found again
          // this can only mean that block is being unapplied
          // we need to go back to waiting for claim
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForClaim(
                None,
                cs.secret,
                cs.currentWalletIdx,
                cs.btcTxId,
                cs.btcVout,
                cs.scriptAsm,
                cs.amount,
                cs.claimAddress
              ),
              warn"Backtracking we are seeing block ${ev.height}, which is smaller or equal to the block where the BTC was claimed (${cs.claimBTCBlockHeight})" >> t2E(
                currentState,
                blockchainEvent
              )
            )
          )
        else
          None
      case (
            cs: WaitingForRedemption,
            be: BifrostFundsWithdrawn
          ) =>
        if (cs.utxoTxId == be.txId && cs.utxoIndex == be.txIndex) {
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForClaim(
                None, // we don't know here in which BTC block we are
                be.secret,
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
        } else None
      case (
            cs: WaitingForClaim,
            ev: NewBTCBlock
          ) =>
        // if we the someStartBtcBlockHeight is empty, we need to set it
        // if it is not empty, we need to check if the number of blocks since waiting is bigger than the threshold
        cs.someStartBtcBlockHeight match {
          case None =>
            Some(
              FSMTransitionTo(
                currentState,
                WaitingForClaim(
                  Some(ev.height),
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
          case Some(startBtcBlockHeight) =>
            if (
              btcRetryThreshold.underlying < (ev.height - startBtcBlockHeight)
            )
              Some(
                FSMTransitionTo(
                  currentState,
                  WaitingForClaim(
                    Some(ev.height), // this will reset the counter
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
            else
              None
        }
      case (
            cs: WaitingForClaim,
            BTCFundsDeposited(depositBTCBlockHeight, scriptPubKey, _, _, _)
          ) =>
        val bech32Address = Bech32Address.fromString(cs.claimAddress)
        if (scriptPubKey == bech32Address.scriptPubKey.asmHex) {
          // the funds were successfully deposited to the claim address
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForClaimBTCConfirmation(
                depositBTCBlockHeight,
                cs.secret,
                cs.currentWalletIdx,
                cs.btcTxId,
                cs.btcVout,
                cs.scriptAsm,
                cs.amount,
                cs.claimAddress
              ),
              t2E(
                currentState,
                blockchainEvent
              )
            )
          )
        } else None
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
            cs: MintingTBTCConfirmation,
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
            cs: MintingTBTCConfirmation,
            be: NewToplBlock
          ) =>
        if (isAboveConfirmationThresholdTopl(be.height, cs.depositTBTCBlockHeight)) {
          import co.topl.brambl.syntax._
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForRedemption(
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
              MintingTBTCConfirmation(
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
            _: WaitingForRedemption,
            _
          ) =>
        None // No transition
      case (
            _: WaitingForClaim,
            _
          ) =>
        None // No transition
      case (_: MMintingTBTC, _) =>
        None // No transition
      case (_: MConfirmingBTCDeposit, _) =>
        None // No transition
      case (_: WaitingForClaimBTCConfirmation, _) =>
        None // No transition
      case (_: MintingTBTCConfirmation, _) =>
        None // No transition
      case (
            _: MWaitingForBTCDeposit,
            _
          ) =>
        None // No transition
    })
}
