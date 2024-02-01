package co.topl.bridge.services

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import co.topl.bridge.BitcoinUtils
import co.topl.bridge.services.StartSessionModule
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.ConfirmRedemptionRequest
import co.topl.shared.ConfirmRedemptionResponse
import co.topl.shared.utils.KeyGenerationUtils
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
      keyfile: String,
      password: String,
      sessionManager: SessionManagerAlgebra[F],
      btcWalletManager: Resource[F, BTCWallet[F]],
      btcNetwork: BitcoinNetworkIdentifiers
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
      sessionInfo <- sessionManager.getSession(req.secret)
      nextPubKey <- btcWalletManager.use(_.getNextPubKey())
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
      km <- KeyGenerationUtils.loadKeyManager[F](btcNetwork, keyfile, password)
      signature <- KeyGenerationUtils.signWithKeyManager[F](
        km,
        signableBytes.bytes,
        0
      )
      bridgeSig = NonStandardScriptSignature.fromAsm(
        Seq(
          ScriptConstant.fromBytes(
            ByteVector(req.secret.getBytes().padTo(32, 0.toByte))
          ),
          ScriptConstant(
            signature
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
