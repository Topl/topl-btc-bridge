package co.topl.bridge.statemachine.pegin


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
