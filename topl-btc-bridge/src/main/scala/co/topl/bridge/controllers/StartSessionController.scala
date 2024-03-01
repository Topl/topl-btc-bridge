package co.topl.bridge.controllers

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import co.topl.bridge.managers.BTCWalletAlgebra
import co.topl.bridge.managers.PeginSessionInfo
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.utils.BitcoinUtils
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.BridgeError
import co.topl.shared.InvalidHash
import co.topl.shared.InvalidKey
import co.topl.shared.StartPeginSessionRequest
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

  private def createPeginSessionInfo[F[_]: Sync](
      currentWalletIdx: Int,
      sha256: String,
      pUserPKey: String,
      bridgePKey: String,
      blockToRecover: Int,
      btcNetwork: BitcoinNetworkIdentifiers
  ): F[(String, PeginSessionInfo)] = {
    import cats.implicits._
    for {
      hash <- Sync[F].fromOption(
        ByteVector.fromHex(sha256),
        InvalidHash(s"Invalid hash $sha256")
      )
      userPKey <- Sync[F]
        .delay(ECPublicKey.fromHex(pUserPKey))
        .handleError(_ => throw InvalidKey(s"Invalid key $pUserPKey"))
      asm =
        BitcoinUtils.buildScriptAsm(
          userPKey,
          ECPublicKey.fromHex(bridgePKey),
          hash,
          blockToRecover
        )
      scriptAsm = BytesUtil.toByteVector(asm)
      scriptHash = CryptoUtil.sha256(scriptAsm)
      push_op = BitcoinScriptUtil.calculatePushOp(hash)
      address = Bech32Address
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
    } yield (address, PeginSessionInfo(
      currentWalletIdx,
      scriptAsm.toHex
    ))
  }

  def startPeginSession[F[_]: Async](
      req: StartPeginSessionRequest,
      pegInWalletManager: BTCWalletAlgebra[F],
      sessionManager: SessionManagerAlgebra[F],
      blockToRecover: Int,
      btcNetwork: BitcoinNetworkIdentifiers
  ): F[Either[BridgeError, StartSessionResponse]] = {
    import cats.implicits._
    (for {
      idxAndnewKey <- pegInWalletManager.getCurrentPubKeyAndPrepareNext()
      (idx, newKey) = idxAndnewKey
      addressAndsessionInfo <- createPeginSessionInfo(
        idx,
        req.sha256,
        req.pkey,
        newKey.hex,
        blockToRecover,
        btcNetwork
      )
      (address, sessionInfo) = addressAndsessionInfo
      sessionId <- sessionManager.createNewSession(sessionInfo)
    } yield StartSessionResponse(
      sessionId,
      sessionInfo.scriptAsm,
      address,
      BitcoinUtils.createDescriptor(newKey.hex, req.pkey, req.sha256)
    ).asRight[BridgeError]).handleError { case e: BridgeError =>
      Left(e)
    }
  }

  // def startPegoutSession[F[_]: Async](
  //     req: StartPegoutSessionRequest,
  //     toplWalletAlgebra: ToplWalletAlgebra[F],
  //     sessionManager: SessionManagerAlgebra[F],
  //     blockToRecover: Int,
  //     btcNetwork: BitcoinNetworkIdentifiers
  // ): F[Either[BridgeError, StartSessionResponse]] = {
  //   import cats.implicits._
  //   (for {
  //     idxAndnewKey <- toplWalletAlgebra.getCurrentPubKeyAndPrepareNext()
  //     (idx, newKey) = idxAndnewKey
  //     sessionInfo <- createPeginSessionInfo(
  //       idx,
  //       req.sha256,
  //       req.pkey,
  //       newKey.hex,
  //       blockToRecover,
  //       btcNetwork
  //     )
  //     sessionId <- sessionManager.createNewSession(sessionInfo)
  //   } yield StartSessionResponse(
  //     sessionId,
  //     sessionInfo.scriptAsm,
  //     sessionInfo.address,
  //     BitcoinUtils.createDescriptor(newKey.hex, req.pkey, req.sha256)
  //   ).asRight[BridgeError]).handleError { case e: BridgeError =>
  //     Left(e)
  //   }
  // }

}
