package co.topl.bridge.controllers

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import co.topl.bridge.managers.BTCWalletAlgebra
import co.topl.bridge.managers.PeginSessionInfo
import co.topl.bridge.managers.PegoutSessionInfo
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.bridge.utils.BitcoinUtils
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.BridgeError
import co.topl.shared.InvalidHash
import co.topl.shared.InvalidKey
import co.topl.shared.StartPeginSessionRequest
import co.topl.shared.StartPegoutSessionRequest
import co.topl.shared.StartPeginSessionResponse
import co.topl.shared.ToplNetworkIdentifiers
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.core.protocol.script.WitnessScriptPubKey
import org.bitcoins.core.script.constant.OP_0
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.util.BitcoinScriptUtil
import org.bitcoins.core.util.BytesUtil
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.crypto._
import scodec.bits.ByteVector

import java.util.UUID
import co.topl.shared.StartPegoutSessionResponse
import quivr.models.KeyPair
import co.topl.shared.WalletSetupError
import co.topl.bridge.PeginSessionState

object StartSessionController {

  private def createPeginSessionInfo[F[_]: Sync](
      currentWalletIdx: Int,
      mintTemplateName: String,
      sha256: String,
      pUserPKey: String,
      bridgePKey: String,
      blockToRecover: Int,
      btcNetwork: BitcoinNetworkIdentifiers,
      toplBridgePKey: String,
      redeemAddress: String
  ): F[(String, PeginSessionInfo)] = {
    import cats.implicits._
    for {
      hash <- Sync[F].fromOption(
        ByteVector.fromHex(sha256.toLowerCase()),
        InvalidHash(s"Invalid hash $sha256")
      )
      _ <- Sync[F].delay(
        if (hash.size != 32)
          throw InvalidHash(s"Sha length is too short, only ${hash.size} bytes")
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
    } yield (
      address,
      PeginSessionInfo(
        currentWalletIdx,
        mintTemplateName,
        redeemAddress,
        scriptAsm.toHex,
        toplBridgePKey,
        sha256,
        PeginSessionState.PeginSessionStateWaitingForBTC
      )
    )
  }

  def startPeginSession[F[_]: Async](
      req: StartPeginSessionRequest,
      pegInWalletManager: BTCWalletAlgebra[F],
      sessionManager: SessionManagerAlgebra[F],
      blockToRecover: Int,
      keyPair: KeyPair,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      btcNetwork: BitcoinNetworkIdentifiers
  ): F[Either[BridgeError, StartPeginSessionResponse]] = {
    import cats.implicits._
    (for {
      idxAndnewKey <- pegInWalletManager.getCurrentPubKeyAndPrepareNext()
      (idx, newKey) = idxAndnewKey
      mintTemplateName <- Sync[F].delay(UUID.randomUUID().toString)
      fromFellowship = mintTemplateName
      someRedeemAdressAndKey <- toplWalletAlgebra.setupBridgeWalletForMinting(
        fromFellowship,
        mintTemplateName,
        keyPair,
        req.sha256
      )
      someRedeemAdress = someRedeemAdressAndKey.map(_._1)
      _ = assert(
        someRedeemAdress.isDefined,
        "Redeem address was not generated correctly"
      )
      bridgeToplKey = someRedeemAdressAndKey.map(_._2).get
      addressAndsessionInfo <- createPeginSessionInfo(
        idx,
        mintTemplateName,
        req.sha256,
        req.pkey,
        newKey.hex,
        blockToRecover,
        btcNetwork,
        bridgeToplKey,
        someRedeemAdress.get
      )
      (address, sessionInfo) = addressAndsessionInfo
      sessionId <- sessionManager.createNewSession(sessionInfo)
    } yield StartPeginSessionResponse(
      sessionId,
      sessionInfo.scriptAsm,
      address,
      BitcoinUtils.createDescriptor(newKey.hex, req.pkey, req.sha256)
    ).asRight[BridgeError]).handleError { case e: BridgeError =>
      Left(e)
    }
  }

  def startPegoutSession[F[_]: Async](
      req: StartPegoutSessionRequest,
      toplNetwork: ToplNetworkIdentifiers,
      keyPair: KeyPair,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      sessionManager: SessionManagerAlgebra[F],
      waitTime: Int
  ): F[Either[BridgeError, StartPegoutSessionResponse]] = {
    import cats.implicits._
    (for {
      fellowshipId <- Sync[F].delay(UUID.randomUUID().toString)
      newAddress <- toplWalletAlgebra
        .setupBridgeWallet(
          toplNetwork,
          keyPair,
          req.userBaseKey,
          fellowshipId,
          fellowshipId,
          req.sha256,
          waitTime,
          req.currentHeight
        )
        .map(_.getOrElse(throw new WalletSetupError("Failed to create wallet")))
      sessionInfo = PegoutSessionInfo(
        fellowshipId,
        newAddress
      )
      sessionId <- sessionManager.createNewSession(sessionInfo)
    } yield StartPegoutSessionResponse(
      sessionId,
      newAddress
    ).asRight[BridgeError]).handleError { case e: BridgeError =>
      Left(e)
    }
  }

}
