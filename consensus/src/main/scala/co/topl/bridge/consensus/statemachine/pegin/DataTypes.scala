package co.topl.bridge.consensus.statemachine.pegin

import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.script.ScriptPubKey
import co.topl.bridge.consensus.BifrostCurrencyUnit

sealed trait BlockchainEvent

case class BTCFundsWithdrawn(txId: String, vout: Long) extends BlockchainEvent

case class NewBTCBlock(height: Int) extends BlockchainEvent

case class SkippedBTCBlock(height: Int) extends BlockchainEvent

case class SkippedToplBlock(height: Long) extends BlockchainEvent

case class NewToplBlock(height: Long) extends BlockchainEvent

case class BTCFundsDeposited(
    fundsDepositedHeight: Int,
    scriptPubKey: ScriptPubKey,
    txId: String,
    vout: Long,
    amount: CurrencyUnit
) extends BlockchainEvent
case class BifrostFundsDeposited(
    currentToplBlockHeight: Long,
    address: String,
    utxoTxId: String,
    utxoIndex: Int,
    amount: BifrostCurrencyUnit
) extends BlockchainEvent

case class BifrostFundsWithdrawn(
    txId: String,
    txIndex: Int,
    secret: String,
    amount: BifrostCurrencyUnit
) extends BlockchainEvent

sealed trait PeginStateMachineState

case class WaitingForBTC(
    currentBTCBlockHeight: Int,
    currentWalletIdx: Int,
    scriptAsm: String,
    escrowAddress: String,
    redeemAddress: String,
    claimAddress: String
) extends PeginStateMachineState

case class WaitingForEscrowBTCConfirmation(
    startBTCBlockHeight: Int,
    depositBTCBlockHeight: Int,
    currentWalletIdx: Int,
    scriptAsm: String,
    escrowAddress: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    amount: Long
) extends PeginStateMachineState

case class MintingTBTC(
    startBTCBlockHeight: Int,
    currentWalletIdx: Int,
    scriptAsm: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    amount: Long
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
