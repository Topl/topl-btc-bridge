package co.topl.bridge.statemachine

import _root_.co.topl.brambl.models.box.Value
import _root_.co.topl.brambl.utils.Encoding
import _root_.co.topl.bridge.AssetToken
import _root_.co.topl.bridge.BifrostCurrencyUnit
import _root_.co.topl.bridge.GroupToken
import _root_.co.topl.bridge.Lvl
import _root_.co.topl.bridge.SeriesToken
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.script.ScriptPubKey

package object pegin {

  sealed trait BlockchainEvent

  case class BTCFundsWithdrawn(txId: String, vout: Long) extends BlockchainEvent
  
  case class BTCFundsDeposited(
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


  def toCurrencyUnit(value: Value.Value) = if (value.isLvl)
    Lvl(value.lvl.get.quantity)
  else if (value.isSeries)
    SeriesToken(
      Encoding.encodeToBase58(
        value.series.get.seriesId.value.toByteArray()
      ),
      value.series.get.quantity
    )
  else if (value.isGroup)
    GroupToken(
      Encoding.encodeToBase58(
        value.group.get.groupId.value.toByteArray()
      ),
      value.group.get.quantity
    )
  else
    AssetToken(
      Encoding.encodeToBase58(
        value.asset.get.groupId.get.value.toByteArray()
      ),
      Encoding.encodeToBase58(
        value.asset.get.seriesId.get.value
          .toByteArray()
      ),
      value.asset.get.quantity
    )
}
