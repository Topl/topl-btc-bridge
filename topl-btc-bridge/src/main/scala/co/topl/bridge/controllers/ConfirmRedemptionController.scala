package co.topl.bridge.controllers

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import co.topl.bridge.managers.BTCWalletAlgebra
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.utils.BitcoinUtils
import co.topl.shared.ConfirmRedemptionRequest
import co.topl.shared.ConfirmRedemptionResponse
import io.circe.generic.auto._
import org.bitcoins.core.currency.SatoshisLong
import org.bitcoins.core.protocol.script.NonStandardScriptSignature
import org.bitcoins.core.protocol.script.P2WSHWitnessV0
import org.bitcoins.core.protocol.script.RawScriptPubKey
import org.bitcoins.core.protocol.transaction.WitnessTransaction
import org.bitcoins.core.script.constant.OP_0
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.crypto._
import org.http4s.circe._
import scodec.bits.ByteVector

object ConfirmRedemptionController {

  def confirmRedemption[F[_]: Async](
      req: ConfirmRedemptionRequest,
      pegInWalletManager: BTCWalletAlgebra[F],
      walletManager: BTCWalletAlgebra[F],
      sessionManager: SessionManagerAlgebra[F]
  ) = {
    import io.circe.syntax._
    import cats.implicits._
    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._
    (for {
      sessionInfo <- sessionManager.getSession(req.sessionID)
      nextPubKey <- walletManager.getCurrentPubKey()
      tx = BitcoinUtils.createRedeemingTx(
        req.inputTxId,
        req.inputIndex,
        req.amount,
        req.feePerByte,
        nextPubKey
      )
      srp = RawScriptPubKey.fromAsmHex(sessionInfo.scriptAsm)
      serializedTxForSignature =
        BitcoinUtils.serializeForSignature(tx, req.amount.satoshis, srp.asm)
      signableBytes = CryptoUtil.doubleSHA256(serializedTxForSignature)
      signature <- pegInWalletManager.signForIdx(
        sessionInfo.currentWalletIdx,
        signableBytes.bytes
      )
      bridgeSig = NonStandardScriptSignature.fromAsm(
        Seq(
          ScriptConstant.fromBytes(
            ByteVector(req.secret.getBytes().padTo(32, 0.toByte))
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
      resp <- Ok(
        ConfirmRedemptionResponse(
          txWit.hex
        ).asJson
      )
    } yield resp).handleErrorWith(e => {
      Sync[F].delay(e.printStackTrace()) *>
        BadRequest("Error")
    })
  }
}
