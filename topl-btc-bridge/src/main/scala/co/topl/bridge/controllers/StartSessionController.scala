package co.topl.bridge.controllers

import cats.effect.kernel.Async
import co.topl.bridge.managers.BTCWalletAlgebra
import co.topl.bridge.managers.SessionInfo
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.utils.BitcoinUtils
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.StartSessionRequest
import co.topl.shared.StartSessionResponse
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.core.protocol.script.WitnessScriptPubKey
import org.bitcoins.core.script.constant.OP_0
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.util.BitcoinScriptUtil
import org.bitcoins.core.util.BytesUtil
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.crypto._
import scodec.bits.ByteVector

object StartSessionController {

  private def createSessionInfo(
      currentWalletIdx: Int,
      sha256: String,
      userPKey: String,
      bridgePKey: String,
      btcNetwork: BitcoinNetworkIdentifiers
  ): SessionInfo = {
    val hash = ByteVector.fromHex(sha256).get
    val asm =
      BitcoinUtils.buildScriptAsm(
        ECPublicKey.fromHex(userPKey),
        ECPublicKey.fromHex(bridgePKey),
        hash,
        1000L
      )
    val scriptAsm = BytesUtil.toByteVector(asm)
    val scriptHash = CryptoUtil.sha256(scriptAsm)
    val push_op = BitcoinScriptUtil.calculatePushOp(hash)
    val address = Bech32Address
      .apply(
        WitnessScriptPubKey
          .apply(
            Seq(OP_0) ++
              push_op ++
              Seq(ScriptConstant.fromBytes(scriptHash.bytes))
          ),
        btcNetwork.btcNetwork
      )
      .value
    SessionInfo(
      bridgePKey,
      currentWalletIdx,
      userPKey,
      sha256,
      scriptAsm.toHex,
      address
    )
  }

  def startSession[F[_]: Async](
      req: StartSessionRequest,
      pegInWalletManager: BTCWalletAlgebra[F],
      sessionManager: SessionManagerAlgebra[F],
      btcNetwork: BitcoinNetworkIdentifiers
  ) = {
    import cats.implicits._
    (for {
      idxAndnewKey <- pegInWalletManager.getCurrentPubKeyAndPrepareNext()
      (idx, newKey) = idxAndnewKey
      sessionInfo = createSessionInfo(
        idx,
        req.sha256,
        req.pkey,
        newKey.hex,
        btcNetwork
      )
      sessionId <- sessionManager.createNewSession(sessionInfo)
    } yield StartSessionResponse(
      sessionId,
      sessionInfo.scriptAsm,
      sessionInfo.address,
      BitcoinUtils.createDescriptor(newKey.hex, req.pkey, req.sha256)
    ))
  }

}
