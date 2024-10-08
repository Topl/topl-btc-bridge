package co.topl.bridge.consensus.subsystems.monitor


import co.topl.bridge.consensus.shared.BifrostCurrencyUnit
import org.bitcoins.core.currency.CurrencyUnit

sealed trait BlockchainEvent

case class BTCFundsWithdrawn(txId: String, vout: Long) extends BlockchainEvent

case class NewBTCBlock(height: Int) extends BlockchainEvent

case class SkippedBTCBlock(height: Int) extends BlockchainEvent

case class SkippedToplBlock(height: Long) extends BlockchainEvent

case class NewToplBlock(height: Long) extends BlockchainEvent

case class BTCFundsDeposited(
    fundsDepositedHeight: Int,
    scriptPubKey: String,
    txId: String,
    vout: Int,
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
    fundsWithdrawnHeight: Long,
    txId: String,
    txIndex: Int,
    secret: String,
    amount: BifrostCurrencyUnit
) extends BlockchainEvent
