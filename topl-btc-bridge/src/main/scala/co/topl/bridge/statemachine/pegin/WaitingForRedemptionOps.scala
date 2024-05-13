package co.topl.bridge.statemachine.pegin

import cats.effect.kernel.Async
import co.topl.bridge.managers.BTCWalletAlgebra
import co.topl.bridge.utils.BitcoinUtils
import org.bitcoins.core.currency.SatoshisLong
import org.bitcoins.core.protocol.script.NonStandardScriptSignature
import org.bitcoins.core.protocol.script.P2WSHWitnessV0
import org.bitcoins.core.protocol.script.RawScriptPubKey
import org.bitcoins.core.protocol.transaction.WitnessTransaction
import org.bitcoins.core.script.constant.OP_0
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.crypto._
import scodec.bits.ByteVector
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.core.currency.CurrencyUnit

object WaitingForRedemptionOps {

  def startClaimingProcess[F[_]: Async](
      secret: String,
      claimAddress: String,
      currentWalletIdx: Int,
      inputTxId: String,
      vout: Long,
      scriptAsm: String,
      amountInSatoshis: Long,
      feePerByte: CurrencyUnit
  )(implicit
      bitcoindInstance: BitcoindRpcClient,
      pegInWalletManager: BTCWalletAlgebra[F]
  ) = {

    import cats.implicits._

    val tx = BitcoinUtils.createRedeemingTx(
      inputTxId,
      vout,
      amountInSatoshis,
      feePerByte,
      claimAddress
    )
    val srp = RawScriptPubKey.fromAsmHex(scriptAsm)
    val serializedTxForSignature =
      BitcoinUtils.serializeForSignature(
        tx,
        amountInSatoshis.satoshis,
        srp.asm
      )
    val signableBytes = CryptoUtil.doubleSHA256(serializedTxForSignature)
    for {
      signature <- pegInWalletManager.signForIdx(
        currentWalletIdx,
        signableBytes.bytes
      )
      bridgeSig = NonStandardScriptSignature.fromAsm(
        Seq(
          ScriptConstant.fromBytes(
            ByteVector(secret.getBytes().padTo(32, 0.toByte))
          ),
          ScriptConstant(
            signature.hex
          ), // signature of bridge
          OP_0
        )
      )
      txWit = WitnessTransaction
        .toWitnessTx(tx)
        .updateWitness(
          0,
          P2WSHWitnessV0(
            srp,
            bridgeSig
          )
        )
      _ <- Async[F].start(
        Async[F].delay(bitcoindInstance.sendRawTransaction(txWit))
      )
    } yield ()
  }

}
