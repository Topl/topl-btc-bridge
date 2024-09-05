package co.topl.bridge.consensus.core.pbft

import co.topl.bridge.consensus.shared.BifrostCurrencyUnit
import org.bitcoins.core.currency.CurrencyUnit

trait PBFTEvent {
  val sessionId: String
}

/** Event that is emitted when a BTC deposit is posted.
  *
  * @param sessionId
  *   the session id of the deposit.
  * @param height
  *   the height of the BTC network where the deposit took place.
  * @param txId
  *   the tx id of the deposit.
  * @param vout
  *   the vout of the deposit.
  * @param amount
  *   the amount of the deposit.
  */
case class PostDepositBTCEvt(
    sessionId: String,
    height: Int,
    txId: String,
    vout: Int,
    amount: CurrencyUnit
) extends PBFTEvent

/** Event that is emitted when a BTC deposit is confirmed.
  *
  * @param sessionId
  *   the session id of the deposit.
  * @param height
  *   the height of the BTC network where the deposit got confirmed.
  */
case class ConfirmDepositBTCEvt(
    sessionId: String,
    height: Int
) extends PBFTEvent

/** Event that is emitted when a the TBTC minting is posted to the chain.
  *
  * @param sessionId
  *   the session id of the minting.
  * @param height
  *   the height of the TBTC network where the minting took place.
  * @param utxoTxId
  *   the tx id of the minting.
  * @param utxoIdx
  *   the index of the minting.
  * @param amount
  *   the amount of the minting.
  */
case class PostTBTCMintEvt(
    sessionId: String,
    height: Long,
    utxoTxId: String,
    utxoIdx: Int,
    amount: BifrostCurrencyUnit
) extends PBFTEvent

/** Event that is emitted when a BTC deposit is undone because of a reorg.
  *
  * @param sessionId
  */
case class UndoDepositBTCEvt(
    sessionId: String
) extends PBFTEvent

/** Event that is emitted when a TBTC minting is undone because of a reorg.
  *
  * @param sessionId
  */
case class UndoTBTCMintEvt(
    sessionId: String
) extends PBFTEvent

/** Event that is emitted when a TBTC minting is confirmed.
  *
  * @param sessionId
  *   the session id of the minting.
  * @param height
  *   the height of the TBTC network where the minting got confirmed.
  */
case class ConfirmTBTCMintEvt(
    sessionId: String,
    height: Long
) extends PBFTEvent

/** Event that is emitted when a TBTC redemption is posted to the chain.
  *
  * @param sessionId
  *   the session id of the redemption.
  * @param height
  *   the height of the TBTC network where the redemption took place.
  * @param utxoTxId
  *   the tx id of the redemption.
  * @param utxoIdx
  *   the index of the redemption.
  * @param amount
  *   the amount of the redemption.
  */
case class PostRedemptionTxEvt(
    sessionId: String,
    secret: String,
    height: Long,
    utxoTxId: String,
    utxoIdx: Int,
    amount: BifrostCurrencyUnit
) extends PBFTEvent

/** Event that is emitted when a TBTC redemption is confirmed.
  *
  * @param sessionId
  *   the session id of the redemption.
  * @param height
  *   the height of the TBTC network where the redemption got confirmed.
  * @param txId
  *   the tx id of the redemption.
  * @param vout
  *   the vout of the redemption.
  */
case class PostClaimTxEvt(
    sessionId: String,
    height: Int,
    txId: String,
    vout: Int
) extends PBFTEvent

case class TimeoutClaimTxEvt(
    sessionId: String,
    height: Long
) extends PBFTEvent

case class UndoClaimTxEvt(
    sessionId: String
) extends PBFTEvent

case class ConfirmClaimTxEvt(
    sessionId: String,
    height: Int
) extends PBFTEvent
