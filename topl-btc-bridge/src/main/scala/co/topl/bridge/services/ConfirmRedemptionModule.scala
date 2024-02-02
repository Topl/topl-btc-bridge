package co.topl.bridge.services

import cats.effect.kernel.Async
import co.topl.bridge.BitcoinUtils
import co.topl.bridge.services.StartSessionModule
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
import org.http4s._
import org.http4s.circe._
import scodec.bits.ByteVector

trait ConfirmRedemptionModule {

  self: StartSessionModule =>

  def confirmRedemption[F[_]: Async](
      req: Request[F],
      pegInWalletManager: BTCWalletAlgebra[F],
      walletManager: BTCWalletAlgebra[F],
      sessionManager: SessionManagerAlgebra[F]
  ) = {
    implicit val confirmRedemptionRequestDecoder
        : EntityDecoder[F, ConfirmRedemptionRequest] =
      jsonOf[F, ConfirmRedemptionRequest]
    import io.circe.syntax._
    import cats.implicits._
    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._
    (for {
      req <- req.as[ConfirmRedemptionRequest]
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
      e.printStackTrace()
      BadRequest("Error")
    })
  }
}
