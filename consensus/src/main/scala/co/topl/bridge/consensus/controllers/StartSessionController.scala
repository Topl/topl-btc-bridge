package co.topl.bridge.consensus.controllers

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.BTCWaitExpirationTime
import co.topl.bridge.consensus.BitcoinNetworkIdentifiers
import co.topl.bridge.consensus.PeginSessionState
import co.topl.bridge.consensus.ToplNetworkIdentifiers
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.managers.BTCWalletAlgebra
import co.topl.bridge.consensus.managers.PeginSessionInfo
import co.topl.bridge.consensus.managers.PegoutSessionInfo
import co.topl.bridge.consensus.managers.SessionManagerAlgebra
import co.topl.bridge.consensus.managers.ToplWalletAlgebra
import co.topl.bridge.consensus.service.StartSessionOperation
import co.topl.bridge.consensus.utils.BitcoinUtils
import co.topl.shared.BridgeError
import co.topl.shared.InvalidHash
import co.topl.shared.InvalidInput
import co.topl.shared.InvalidKey
import co.topl.shared.StartPeginSessionResponse
import co.topl.shared.StartPegoutSessionRequest
import co.topl.shared.StartPegoutSessionResponse
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
import org.typelevel.log4cats.Logger
import quivr.models.KeyPair
import scodec.bits.ByteVector

import java.util.UUID
import co.topl.bridge.consensus.managers.SessionInfo

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
        sha256,
        minHeight,
        maxHeight,
        claimAddress,
        PeginSessionState.PeginSessionStateWaitingForBTC
      )
    )
  }

  def startPeginSession[F[_]: Async: Logger](
      sessionId: String,
      req: StartSessionOperation,
      pegInWalletManager: BTCWalletAlgebra[F],
      bridgeWalletManager: BTCWalletAlgebra[F],
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
  ): F[Either[BridgeError, (PeginSessionInfo, StartPeginSessionResponse)]] = {
    import cats.implicits._
    import ToplWalletAlgebra._

    import org.typelevel.log4cats.syntax._

    (for {
      idxAndnewKey <- pegInWalletManager.getCurrentPubKeyAndPrepareNext()
      (btcPeginCurrentWalletIdx, btcPeginBridgePKey) = idxAndnewKey
      bridgeIdxAndnewKey <- bridgeWalletManager.getCurrentPubKeyAndPrepareNext()
      (btcBridgeCurrentWalletIdx, btcBridgePKey) = bridgeIdxAndnewKey
      mintTemplateName <- Sync[F].delay(UUID.randomUUID().toString)
      fromFellowship = mintTemplateName
      minToplHeight <- currentToplHeight.get
      _ <-
        if (minToplHeight == 0)
          Sync[F].raiseError(new IllegalStateException("Topl height is 0"))
        else Sync[F].unit
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
        someRedeemAdress.get,
        minToplHeight,
        maxToplHeight
      )
      (address, sessionInfo) = addressAndsessionInfo
    } yield (
      sessionInfo,
      StartPeginSessionResponse(
        sessionId,
        sessionInfo.scriptAsm,
        address,
        BitcoinUtils
          .createDescriptor(btcPeginBridgePKey.hex, req.pkey, req.sha256),
        minToplHeight,
        maxToplHeight
      )
    ).asRight[BridgeError]).handleErrorWith {
      case e: BridgeError =>
        error"Error handling start pegin session request: $e"
        Sync[F].delay(Left(e))
      case t: Throwable =>
        error"Error handling start pegin session request $t" >> Sync[F].delay(
          Left(InvalidInput("Unknown error"))
        )
    }
  }

  // def startPegoutSession[F[_]: Async](
  //     req: StartPegoutSessionRequest,
  //     toplNetwork: ToplNetworkIdentifiers,
  //     keyPair: KeyPair,
  //     sessionManager: SessionManagerAlgebra[F],
  //     waitTime: Int
  // )(implicit
  //     fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
  //     templateStorageAlgebra: TemplateStorageAlgebra[F],
  //     walletApi: WalletApi[F],
  //     wsa: WalletStateAlgebra[F]
  // ): F[Either[BridgeError, StartPegoutSessionResponse]] = {
  //   import cats.implicits._
  //   import ToplWalletAlgebra._
  //   (for {
  //     fellowshipId <- Sync[F].delay(UUID.randomUUID().toString)
  //     newAddress <- setupBridgeWallet(
  //       toplNetwork,
  //       keyPair,
  //       req.userBaseKey,
  //       fellowshipId,
  //       fellowshipId,
  //       req.sha256,
  //       waitTime,
  //       req.currentHeight
  //     )
  //       .map(_.getOrElse(throw new WalletSetupError("Failed to create wallet")))
  //     sessionInfo = PegoutSessionInfo(
  //       fellowshipId,
  //       newAddress
  //     )
  //     sessionId <- sessionManager.createNewSession(sessionInfo)
  //   } yield StartPegoutSessionResponse(
  //     sessionId,
  //     newAddress
  //   ).asRight[BridgeError]).handleError { case e: BridgeError =>
  //     Left(e)
  //   }
  // }

}
