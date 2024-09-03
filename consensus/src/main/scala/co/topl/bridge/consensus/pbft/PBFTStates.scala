package co.topl.bridge.consensus.pbft

import co.topl.bridge.consensus.BifrostCurrencyUnit
import org.bitcoins.core.currency.CurrencyUnit
import co.topl.bridge.consensus.Lvl
import co.topl.bridge.consensus.SeriesToken
import co.topl.bridge.consensus.GroupToken
import co.topl.bridge.consensus.AssetToken

sealed trait PBFTState {
  def toBytes: Array[Byte]
}

/** State where we are waiting for a BTC deposit to be confirmed
  *
  * @param height
  *   height where we started waiting for the deposit
  * @param currentWalletIdx
  *   index of the current BTC wallet of the bridge
  * @param scriptAsm
  *   the script asm of the escrow address
  * @param escrowAddress
  *   the escrow address (on BTC)
  * @param redeemAddress
  *   the redeem address (on the Topl Network)
  * @param claimAddress
  *   the claim address (on the BTC), this is the address where the BTC will be
  *   sent to after redemption is confirmed
  */
case class PSWaitingForBTCDeposit(
    height: Int,
    currentWalletIdx: Int,
    scriptAsm: String,
    escrowAddress: String,
    redeemAddress: String,
    claimAddress: String
) extends PBFTState {

  override def toBytes: Array[Byte] =
    BigInt(height).toByteArray ++
      BigInt(currentWalletIdx).toByteArray ++
      scriptAsm.getBytes ++
      escrowAddress.getBytes ++
      redeemAddress.getBytes ++
      claimAddress.getBytes

}

/** State where we are confirming a BTC deposit.
  *
  * @param startWaitingBTCBlockHeight
  *   height where we started waiting for the deposit
  * @param depositBTCBlockHeight
  *   height where the deposit took place
  * @param currentWalletIdx
  *   index of the current BTC wallet of the bridge
  * @param scriptAsm
  *   the script asm of the escrow address
  * @param escrowAddress
  *   the escrow address (on BTC)
  * @param redeemAddress
  *   the redeem address (on the Topl Network)
  * @param claimAddress
  *   the claim address (on the BTC), this is the address where the BTC will be
  *   sent to after redemption is confirmed
  * @param btcTxId
  *   tx id of the BTC deposit
  * @param btcVout
  *   vout of the BTC deposit
  * @param amount
  *   amount of the BTC deposit
  */
case class PSConfirmingBTCDeposit(
    startWaitingBTCBlockHeight: Int,
    depositBTCBlockHeight: Int,
    currentWalletIdx: Int,
    scriptAsm: String,
    escrowAddress: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    amount: CurrencyUnit
) extends PBFTState {

  override def toBytes: Array[Byte] =
    BigInt(startWaitingBTCBlockHeight).toByteArray ++
      BigInt(depositBTCBlockHeight).toByteArray ++
      BigInt(currentWalletIdx).toByteArray ++
      scriptAsm.getBytes ++
      escrowAddress.getBytes ++
      redeemAddress.getBytes ++
      claimAddress.getBytes ++
      btcTxId.getBytes ++
      BigInt(btcVout).toByteArray ++
      amount.satoshis.toBigInt.toByteArray

}

/** State where we are minting TBTC.
  *
  * @param startWaitingBTCBlockHeight
  *   height where we started waiting for the deposit. We use this for timeout
  *   in case we are not able to never mint.
  * @param currentWalletIdx
  *   index of the current BTC wallet of the bridge
  * @param scriptAsm
  *   the script asm of the escrow address
  * @param redeemAddress
  *   the redeem address (on the Topl Network) where the TBTC will be sent to
  * @param claimAddress
  *   the claim address (on the BTC), this is the address where the BTC will be
  *   sent to after redemption is confirmed
  * @param btcTxId
  *   tx id of the BTC deposit
  * @param btcVout
  *   vout of the BTC deposit
  * @param amount
  *   amount of the BTC deposit
  */
case class PSMintingTBTC(
    startWaitingBTCBlockHeight: Int,
    currentWalletIdx: Int,
    scriptAsm: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    amount: CurrencyUnit
) extends PBFTState {

  override def toBytes: Array[Byte] =
    BigInt(startWaitingBTCBlockHeight).toByteArray ++
      BigInt(currentWalletIdx).toByteArray ++
      scriptAsm.getBytes ++
      redeemAddress.getBytes ++
      claimAddress.getBytes ++
      btcTxId.getBytes ++
      BigInt(btcVout).toByteArray ++
      amount.satoshis.toBigInt.toByteArray

}

/** State where we are waiting for redemption of the TBTC.
  *
  * @param tbtcMintBlockHeight
  *   The block height where the TBTC was minted
  * @param currentWalletIdx
  *   The index of the current BTC wallet of the bridge
  * @param scriptAsm
  *   The script asm of the escrow address
  * @param redeemAddress
  *   The redeem address (on the Topl Network) where the TBTC will be sent to
  * @param claimAddress
  *   The claim address (on the BTC), this is the address where the BTC will be
  *   sent to after redemption is confirmed
  * @param btcTxId
  *   The tx id of the BTC deposit
  * @param btcVout
  *   The vout of the BTC deposit
  * @param utxoTxId
  *   The tx id of the UTXO where the TBTC are stored
  * @param utxoIndex
  *   The index of the UTXO where the TBTC are stored
  * @param amount
  *   The amount of the BTC deposit
  */
case class PSWaitingForRedemption(
    tbtcMintBlockHeight: Long,
    currentWalletIdx: Int,
    scriptAsm: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    utxoTxId: String,
    utxoIndex: Int,
    amount: BifrostCurrencyUnit
) extends PBFTState {

  override def toBytes: Array[Byte] =
    BigInt(tbtcMintBlockHeight).toByteArray ++
      BigInt(currentWalletIdx).toByteArray ++
      scriptAsm.getBytes ++
      redeemAddress.getBytes ++
      claimAddress.getBytes ++
      btcTxId.getBytes ++
      BigInt(btcVout).toByteArray ++
      utxoTxId.getBytes ++
      BigInt(utxoIndex).toByteArray ++
      (amount match {
        case Lvl(amount) => amount.value.toByteArray
        case SeriesToken(seriesId, amount) =>
          seriesId.getBytes ++ amount.value.toByteArray
        case GroupToken(groupId, amount) =>
          groupId.getBytes ++ amount.value.toByteArray
        case AssetToken(groupId, seriesId, amount) =>
          groupId.getBytes ++ seriesId.getBytes ++ amount.value.toByteArray
      })

}

/** State where we are confirming the minting of TBTC. This state is used to
  * confirm that the TBTC minting was successful.
  *
  * @param startWaitingBTCBlockHeight
  *   The block height where we started waiting for the deposit. We use this for
  *   timeout in case we are not able to never mint.
  * @param depositTBTCBlockHeight
  *   The block height where the TBTC was minted
  * @param currentWalletIdx
  *   The index of the current BTC wallet of the bridge
  * @param scriptAsm
  *   The script asm of the escrow address
  * @param redeemAddress
  *   The redeem address (on the Topl Network) where the TBTC will be sent to
  * @param claimAddress
  *   The claim address (on the BTC), this is the address where the BTC will be
  *   sent to after redemption is confirmed
  * @param btcTxId
  *   The tx id of the BTC deposit
  * @param btcVout
  *   The vout of the BTC deposit
  * @param utxoTxId
  *   The tx id of the UTXO where the TBTC are stored
  * @param utxoIndex
  *   The index of the UTXO where the TBTC are stored
  * @param amount
  *   The amount of the TBTC minted
  */
case class PSConfirmingTBTCMint(
    startWaitingBTCBlockHeight: Int,
    depositTBTCBlockHeight: Long,
    currentWalletIdx: Int,
    scriptAsm: String,
    redeemAddress: String,
    claimAddress: String,
    btcTxId: String,
    btcVout: Long,
    utxoTxId: String,
    utxoIndex: Int,
    amount: BifrostCurrencyUnit
) extends PBFTState {

  def toBytes: Array[Byte] =
    BigInt(startWaitingBTCBlockHeight).toByteArray ++
      BigInt(depositTBTCBlockHeight).toByteArray ++
      BigInt(currentWalletIdx).toByteArray ++
      scriptAsm.getBytes ++
      redeemAddress.getBytes ++
      claimAddress.getBytes ++
      btcTxId.getBytes ++
      BigInt(btcVout).toByteArray ++
      utxoTxId.getBytes ++
      BigInt(utxoIndex).toByteArray ++
      (amount match {
        case Lvl(amount) => amount.value.toByteArray
        case SeriesToken(seriesId, amount) =>
          seriesId.getBytes ++ amount.value.toByteArray
        case GroupToken(groupId, amount) =>
          groupId.getBytes ++ amount.value.toByteArray
        case AssetToken(groupId, seriesId, amount) =>
          groupId.getBytes ++ seriesId.getBytes ++ amount.value.toByteArray
      })

}

/** State where we are claiming BTC.
  *
  * @param someStartBtcBlockHeight
  *   Optional block where we started waiting for the claim transaction. This
  *   value is used to retry the claim transaction in case it fails, after a
  *   certain time.
  * @param secret
  *   The secret that is used to claim the BTC
  * @param currentWalletIdx
  *   The index of the current BTC wallet of the bridge
  * @param btcTxId
  *   The tx id of the BTC deposit, we will use this to claim
  * @param btcVout
  *   The vout of the BTC deposit, we will use this to claim
  * @param scriptAsm
  *   The script asm of the escrow address
  * @param amount
  *   The amount of the BTC deposit
  * @param claimAddress
  *   The address where the BTC will be sent to
  */
case class PSClaimingBTC(
    someStartBtcBlockHeight: Option[Int],
    secret: String,
    currentWalletIdx: Int,
    btcTxId: String,
    btcVout: Long,
    scriptAsm: String,
    amount: BifrostCurrencyUnit,
    claimAddress: String
) extends PBFTState {

  def toBytes: Array[Byte] =
    someStartBtcBlockHeight.map(BigInt(_).toByteArray).getOrElse(Array.empty) ++
      secret.getBytes ++
      BigInt(currentWalletIdx).toByteArray ++
      btcTxId.getBytes ++
      BigInt(btcVout).toByteArray ++
      scriptAsm.getBytes ++
      (amount match {
        case Lvl(amount) => amount.value.toByteArray
        case SeriesToken(seriesId, amount) =>
          seriesId.getBytes ++ amount.value.toByteArray
        case GroupToken(groupId, amount) =>
          groupId.getBytes ++ amount.value.toByteArray
        case AssetToken(groupId, seriesId, amount) =>
          groupId.getBytes ++ seriesId.getBytes ++ amount.value.toByteArray
      }) ++
      claimAddress.getBytes

}

/** State where we are confirming the claim of BTC.
  *
  * @param claimBTCBlockHeight
  *   height where the claim transaction was posted
  * @param secret
  *   the secret that is used to claim the BTC, we need this in case we need to
  *   retry the transaction
  * @param currentWalletIdx
  *   index of the current BTC wallet of the bridge
  * @param btcTxId
  *   tx id of the BTC deposit, we will use this to claim
  * @param btcVout
  *   vout of the BTC deposit, we will use this to claim the BTC
  * @param scriptAsm
  *   the script asm of the escrow address
  * @param amount
  *   the amount of the BTC deposit
  * @param claimAddress
  *   the address where the BTC will be sent to
  */
case class PSConfirmingBTCClaim(
    claimBTCBlockHeight: Int,
    secret: String,
    currentWalletIdx: Int,
    btcTxId: String,
    btcVout: Long,
    scriptAsm: String,
    amount: BifrostCurrencyUnit,
    claimAddress: String
) extends PBFTState {

  def toBytes: Array[Byte] =
    BigInt(claimBTCBlockHeight).toByteArray ++
      secret.getBytes ++
      BigInt(currentWalletIdx).toByteArray ++
      btcTxId.getBytes ++
      BigInt(btcVout).toByteArray ++
      scriptAsm.getBytes ++
      (amount match {
        case Lvl(amount) => amount.value.toByteArray
        case SeriesToken(seriesId, amount) =>
          seriesId.getBytes ++ amount.value.toByteArray
        case GroupToken(groupId, amount) =>
          groupId.getBytes ++ amount.value.toByteArray
        case AssetToken(groupId, seriesId, amount) =>
          groupId.getBytes ++ seriesId.getBytes ++ amount.value.toByteArray
      }) ++
      claimAddress.getBytes

}
