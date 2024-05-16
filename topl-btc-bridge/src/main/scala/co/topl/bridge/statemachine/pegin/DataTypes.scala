package co.topl.bridge.statemachine.pegin
import _root_.co.topl.bridge.BifrostCurrencyUnit
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.script.ScriptPubKey

sealed trait BlockchainEvent

case class BTCFundsWithdrawn(blockHeight: Long, txId: String, vout: Long)
    extends BlockchainEvent

case class BTCFundsDeposited(
    blockHeight: Long,
    scriptPubKey: ScriptPubKey,
    txId: String,
    vout: Long,
    amount: CurrencyUnit
) extends BlockchainEvent
case class BifrostFundsDeposited(
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
    currentWalletIdx: Int,
    scriptAsm: String,
    escrowAddress: String,
    redeemAddress: String,
    claimAddress: String
) extends PeginStateMachineState
case class MintingTBTC(
    currentWalletIdx: Int,
    scriptAsm: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    amount: Long
) extends PeginStateMachineState
case class WaitingForRedemption(
    currentWalletIdx: Int,
    scriptAsm: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    utxoTxId: String,
    utxoIndex: Int
) extends PeginStateMachineState
case class WaitingForClaim(claimAddress: String) extends PeginStateMachineState

sealed trait FSMTransition

case class FSMTransitionTo[F[_]](
    prevState: PeginStateMachineState,
    nextState: PeginStateMachineState,
    effect: F[Unit]
) extends FSMTransition

case class EndTrasition[F[_]](
    effect: F[Unit]
) extends FSMTransition
