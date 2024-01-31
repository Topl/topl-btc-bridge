package co.topl.bridge.services

import cats.effect.IO
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
import org.http4s.dsl.io._
import scodec.bits.ByteVector
import scala.collection.mutable

trait ConfirmRedemptionModule {

  self: StartSessionModule =>

  def confirmRedemption(
      req: Request[IO],
      mapRes: Resource[IO, mutable.Map[String, SessionInfo]],
      btcWalletManager: Resource[IO, BTCWallet],
      btcNetwork: BitcoinNetworkIdentifiers
  ) = {
    implicit val confirmRedemptionRequestDecoder
        : EntityDecoder[IO, ConfirmRedemptionRequest] =
      jsonOf[IO, ConfirmRedemptionRequest]
    import io.circe.syntax._
    (for {
      req <- req.as[ConfirmRedemptionRequest]
      sessionInfo <- mapRes.use(map =>
        IO.fromOption(
          map.get(req.sessionID)
        )(new IllegalArgumentException("Invalid session ID"))
      )
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
      signature <- KeyGenerationUtils.loadKeyAndSign[IO](
        btcNetwork,
        req.sessionID + ".json",
        req.sessionID,
        signableBytes.bytes
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
