package co.topl.bridge.controllers

import co.topl.bridge.managers.BTCWalletAlgebra
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.utils.BitcoinUtils
import co.topl.shared.ConfirmRedemptionRequest
import co.topl.shared.ConfirmRedemptionResponse
import org.bitcoins.core.currency.SatoshisLong
import org.bitcoins.core.protocol.script.NonStandardScriptSignature
import org.bitcoins.core.protocol.script.P2WSHWitnessV0
import org.bitcoins.core.protocol.script.RawScriptPubKey
import org.bitcoins.core.protocol.transaction.WitnessTransaction
import org.bitcoins.core.script.constant.OP_0
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.crypto._
import scodec.bits.ByteVector
import co.topl.shared.SessionNotFoundError
import cats.effect.kernel.Sync
import co.topl.shared.BridgeError
import co.topl.bridge.managers.PeginSessionInfo

object ConfirmRedemptionController {

  def confirmRedemption[F[_]: Sync](
      req: ConfirmRedemptionRequest,
      pegInWalletManager: BTCWalletAlgebra[F],
      walletManager: BTCWalletAlgebra[F],
      sessionManager: SessionManagerAlgebra[F]
  ): F[Either[BridgeError, ConfirmRedemptionResponse]] = {
    import cats.implicits._
    (for {
      genericSessionInfo <- sessionManager
        .getSession(req.sessionID)
        .handleError(_ =>
          throw SessionNotFoundError(
            s"Session with id ${req.sessionID} not found"
          )
        )
      sessionInfo = genericSessionInfo match {
        case PeginSessionInfo(
              currentWalletIdx,
              mintTemplateName,
              scriptAsm,
              redeemAddress,
              toplBridgePKey,
              sha256,
              state
            ) =>
          PeginSessionInfo(
            currentWalletIdx,
            mintTemplateName,
            scriptAsm,
            redeemAddress,
            toplBridgePKey,
            sha256,
            state
          )
        case _ =>
          throw new RuntimeException(
            "Session info is not a pegin session info"
          )
      }
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
    } yield ConfirmRedemptionResponse(txWit.hex)
      .asRight[BridgeError]).recover { case e: BridgeError =>
      Left(e)
    }
  }
}
