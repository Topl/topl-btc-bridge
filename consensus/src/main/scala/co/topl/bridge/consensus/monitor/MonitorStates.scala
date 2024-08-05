package co.topl.bridge.consensus.monitor

import co.topl.bridge.consensus.BifrostCurrencyUnit
import org.bitcoins.core.currency.CurrencyUnit


sealed trait PeginStateMachineState

case class MWaitingForBTCDeposit(
    currentBTCBlockHeight: Int,
    currentWalletIdx: Int,
    scriptAsm: String,
    escrowAddress: String,
    redeemAddress: String,
    claimAddress: String
) extends PeginStateMachineState

case class MConfirmingBTCDeposit(
    startBTCBlockHeight: Int,
    depositBTCBlockHeight: Int,
    currentWalletIdx: Int,
    scriptAsm: String,
    escrowAddress: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    amount: CurrencyUnit
) extends PeginStateMachineState

case class MMintingTBTC(
    startBTCBlockHeight: Int,
    currentWalletIdx: Int,
    scriptAsm: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    amount: CurrencyUnit
) extends PeginStateMachineState

case class WaitingForRedemption(
    currentTolpBlockHeight: Long,
    currentWalletIdx: Int,
    scriptAsm: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    utxoTxId: String,
    utxoIndex: Int,
    amount: BifrostCurrencyUnit
) extends PeginStateMachineState

case class MintingTBTCConfirmation(
    startBTCBlockHeight: Int,
    depositTBTCBlockHeight: Long,
    currentWalletIdx: Int,
    scriptAsm: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    utxoTxId: String,
    utxoIndex: Int,
    amount: CurrencyUnit
) extends PeginStateMachineState


case class WaitingForClaim(
    someStartBtcBlockHeight: Option[Int],
    secret: String,
    currentWalletIdx: Int,
    btcTxId: String,
    btcVout: Long,
    scriptAsm: String,
    amount: BifrostCurrencyUnit,
    claimAddress: String
) extends PeginStateMachineState

case class WaitingForClaimBTCConfirmation(
    claimBTCBlockHeight: Int,
    secret: String,
    currentWalletIdx: Int,
    btcTxId: String,
    btcVout: Long,
    scriptAsm: String,
    amount: BifrostCurrencyUnit,
    claimAddress: String
) extends PeginStateMachineState

sealed trait FSMTransition

case class FSMTransitionTo[F[_]](
    prevState: PeginStateMachineState,
    nextState: PeginStateMachineState,
    effect: F[Unit]
) extends FSMTransition

case class EndTransition[F[_]](
    effect: F[Unit]
) extends FSMTransition
