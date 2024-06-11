package co.topl.bridge.controllers

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.PeginSessionState
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
import co.topl.shared.StartPeginSessionResponse
import co.topl.shared.StartPegoutSessionRequest
import co.topl.shared.StartPegoutSessionResponse
import co.topl.shared.ToplNetworkIdentifiers
import co.topl.shared.WalletSetupError
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.core.protocol.script.P2WPKHWitnessSPKV0
import org.bitcoins.core.protocol.script.WitnessScriptPubKey
import org.bitcoins.core.script.constant.OP_0
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.util.BitcoinScriptUtil
import org.bitcoins.core.util.BytesUtil
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.crypto._
import quivr.models.KeyPair
import scodec.bits.ByteVector

import java.util.UUID
import co.topl.bridge.BTCWaitExpirationTime
import cats.effect.kernel.Ref
import co.topl.bridge.ToplWaitExpirationTime

object StartSessionController {

  private def createPeginSessionInfo[F[_]: Sync](
      btcPeginCurrentWalletIdx: Int,
      btcBridgeCurrentWalletIdx: Int,
      mintTemplateName: String,
      sha256: String,
      pUserPKey: String,
      btcPeginBridgePKey: String,
      btcBridgePKey: ECPublicKey,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      btcNetwork: BitcoinNetworkIdentifiers,
      toplBridgePKey: String,
      redeemAddress: String,
      minHeight: Long,
      maxHeight: Long
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
          ECPublicKey.fromHex(btcPeginBridgePKey),
          hash,
          btcWaitExpirationTime.underlying
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
      claimAddress = Bech32Address
        .apply(
          P2WPKHWitnessSPKV0(btcBridgePKey),
          btcNetwork.btcNetwork
        )
        .value

    } yield (
      address,
      PeginSessionInfo(
        btcPeginCurrentWalletIdx,
        btcBridgeCurrentWalletIdx,
        mintTemplateName,
        redeemAddress,
        address,
        scriptAsm.toHex,
        toplBridgePKey,
        sha256,
        minHeight,
        maxHeight,
        claimAddress,
        PeginSessionState.PeginSessionStateWaitingForBTC
      )
    )
  }

  def startPeginSession[F[_]: Async](
      req: StartPeginSessionRequest,
      pegInWalletManager: BTCWalletAlgebra[F],
      bridgeWalletManager: BTCWalletAlgebra[F],
      sessionManager: SessionManagerAlgebra[F],
      keyPair: KeyPair,
      currentToplHeight: Ref[F, Long],
      btcNetwork: BitcoinNetworkIdentifiers
  )(implicit
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
      templateStorageAlgebra: TemplateStorageAlgebra[F],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      tba: TransactionBuilderApi[F],
      walletApi: WalletApi[F],
      wsa: WalletStateAlgebra[F]
  ): F[Either[BridgeError, StartPeginSessionResponse]] = {
    import cats.implicits._
    import ToplWalletAlgebra._
    (for {
      idxAndnewKey <- pegInWalletManager.getCurrentPubKeyAndPrepareNext()
      (btcPeginCurrentWalletIdx, btcPeginBridgePKey) = idxAndnewKey
      bridgeIdxAndnewKey <- bridgeWalletManager.getCurrentPubKeyAndPrepareNext()
      (btcBridgeCurrentWalletIdx, btcBridgePKey) = bridgeIdxAndnewKey
      mintTemplateName <- Sync[F].delay(UUID.randomUUID().toString)
      fromFellowship = mintTemplateName
      minToplHeight <- currentToplHeight.get
      maxToplHeight = minToplHeight + toplWaitExpirationTime.underlying
      someRedeemAdressAndKey <- setupBridgeWalletForMinting(
        fromFellowship,
        mintTemplateName,
        keyPair,
        req.sha256,
        minToplHeight,
        maxToplHeight
      )
      someRedeemAdress = someRedeemAdressAndKey.map(_._1)
      _ = assert(
        someRedeemAdress.isDefined,
        "Redeem address was not generated correctly"
      )
      bridgeBifrostKey = someRedeemAdressAndKey.map(_._2).get
      addressAndsessionInfo <- createPeginSessionInfo(
        btcPeginCurrentWalletIdx,
        btcBridgeCurrentWalletIdx,
        mintTemplateName,
        req.sha256,
        req.pkey,
        btcPeginBridgePKey.hex,
        btcBridgePKey,
        btcWaitExpirationTime,
        btcNetwork,
        bridgeBifrostKey,
        someRedeemAdress.get,
        minToplHeight,
        maxToplHeight
      )
      (address, sessionInfo) = addressAndsessionInfo
      sessionId <- sessionManager.createNewSession(sessionInfo)
    } yield StartPeginSessionResponse(
      sessionId,
      sessionInfo.scriptAsm,
      address,
      BitcoinUtils
        .createDescriptor(btcPeginBridgePKey.hex, req.pkey, req.sha256),
      minToplHeight,
      maxToplHeight
    ).asRight[BridgeError]).handleError { case e: BridgeError =>
      Left(e)
    }
  }

  def startPegoutSession[F[_]: Async](
      req: StartPegoutSessionRequest,
      toplNetwork: ToplNetworkIdentifiers,
      keyPair: KeyPair,
      sessionManager: SessionManagerAlgebra[F],
      waitTime: Int
  )(implicit
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
      templateStorageAlgebra: TemplateStorageAlgebra[F],
      walletApi: WalletApi[F],
      wsa: WalletStateAlgebra[F]
  ): F[Either[BridgeError, StartPegoutSessionResponse]] = {
    import cats.implicits._
    import ToplWalletAlgebra._
    (for {
      fellowshipId <- Sync[F].delay(UUID.randomUUID().toString)
      newAddress <- setupBridgeWallet(
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
