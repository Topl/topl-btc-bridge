package co.topl.bridge.controllers

import cats.Monad
import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.bridge.MintingBTCState
import co.topl.bridge.managers.PeginSessionInfo
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.bridge.managers.TransactionAlgebra
import co.topl.bridge.managers.WalletApiHelpers
import co.topl.genus.services.Txo
import co.topl.shared.BridgeError
import co.topl.shared.ConfirmDepositRequest
import co.topl.shared.ConfirmDepositResponse
import co.topl.shared.InvalidInput
import co.topl.shared.SessionNotFoundError
import com.google.protobuf.ByteString
import org.typelevel.log4cats.Logger
import quivr.models.Int128
import quivr.models.KeyPair
import scala.concurrent.duration._
import co.topl.brambl.codecs.AddressCodecs

class ConfirmDepositController[F[_]: Async: Logger](
    walletStateApi: WalletStateAlgebra[F],
    transactionBuilderApi: TransactionBuilderApi[F]
) extends WalletApiHelpers[F] {

  import org.typelevel.log4cats.syntax._

  val sync: cats.effect.kernel.Sync[F] = implicitly[Sync[F]]

  val m: Monad[F] = implicitly[Monad[F]]

  val wsa: WalletStateAlgebra[F] = walletStateApi

  val tba: TransactionBuilderApi[F] = transactionBuilderApi

  val fromFellowship = "self"
  val fromTemplate = "default"

  import cats.implicits._
  private def getTxosFromMinting(
      genusQueryAlgebra: GenusQueryAlgebra[F],
      redeemAddress: String
  ): F[Seq[Txo]] = {
    for {
      txos <- genusQueryAlgebra.queryUtxo(
        AddressCodecs.decodeAddress(redeemAddress).toOption.get
      )
    } yield txos
  }

  def checkIfAssetTokenMinted(
      address: String,
      sessionID: String,
      sessionInfo: PeginSessionInfo,
      sessionManager: SessionManagerAlgebra[F],
      genusQueryAlgebra: GenusQueryAlgebra[F]
  ): F[Unit] = for {
    newTxos <- getTxosFromMinting(genusQueryAlgebra, sessionInfo.redeemAddress)
    _ <-
      if (newTxos.filter(_.transactionOutput.value.value.isAsset).nonEmpty) {
        info"tBTC minted successfully to address $address" >>
          sessionManager.updateSession(
            sessionID,
            sessionInfo.copy(
              mintingBTCState = MintingBTCState.MintingBTCStateMinted(address)
            )
          )
      } else {
        info"tBTC not minted, checking txos in 5 seconds" >>
          sessionManager.updateSession(
            sessionID,
            sessionInfo.copy(
              mintingBTCState = MintingBTCState.MintingBTCStateWaiting
            )
          ) >> Async[F].sleep(5.second) >> checkIfAssetTokenMinted(
            address,
            sessionID,
            sessionInfo,
            sessionManager,
            genusQueryAlgebra
          )
      }
  } yield ()

  def confirmDeposit(
      keyPair: KeyPair,
      confirmDepositRequest: ConfirmDepositRequest,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      transactionAlgebra: TransactionAlgebra[F],
      utxoAlgebra: GenusQueryAlgebra[F],
      fee: Long,
      sessionManager: SessionManagerAlgebra[F]
  ): F[Either[BridgeError, ConfirmDepositResponse]] = {
    import cats.implicits._
    val fromFellowship = "self"
    val fromTemplate = "default"
    // import address codecs
    (for {
      genericSessionInfo <- sessionManager
        .getSession(confirmDepositRequest.sessionID)
        .handleError(_ =>
          throw SessionNotFoundError(
            s"Session with id ${confirmDepositRequest.sessionID} not found"
          )
        )
      sessionInfo = genericSessionInfo match {
        case PeginSessionInfo(
              currentWalletIdx,
              mintTemplateName,
              redeemAddress,
              scriptAsm,
              state
            ) =>
          PeginSessionInfo(
            currentWalletIdx,
            mintTemplateName,
            redeemAddress,
            scriptAsm,
            state
          )
        case _ =>
          throw new RuntimeException(
            "Session info is not a pegin session info"
          )
      }
      currentAddress <- getCurrentAddress(
        fromFellowship,
        fromTemplate,
        None
      )
      txos <- utxoAlgebra.queryUtxo(
        currentAddress
      )
      groupTokenUtxo = txos
        .filter(_.transactionOutput.value.value.isGroup)
        .head
        .outputAddress
      seriesTokenUtxo = txos
        .filter(_.transactionOutput.value.value.isSeries)
        .head
        .outputAddress
      assetMintingStatement = AssetMintingStatement(
        groupTokenUtxo,
        seriesTokenUtxo,
        Int128(
          ByteString.copyFrom(BigInt(confirmDepositRequest.amount).toByteArray)
        )
      )
      ioTransaction <- toplWalletAlgebra
        .createSimpleAssetMintingTransactionFromParams(
          keyPair,
          fromFellowship,
          fromTemplate,
          None,
          fee,
          None,
          None,
          assetMintingStatement,
          sessionInfo.redeemAddress
        )
      provedIoTx <- transactionAlgebra
        .proveSimpleTransactionFromParams(
          ioTransaction,
          keyPair
        )
        .flatMap(Sync[F].fromEither(_))
      txId <- transactionAlgebra
        .broadcastSimpleTransactionFromParams(provedIoTx)
        .flatMap(Sync[F].fromEither(_))
      _ <- sessionManager.updateSession(
        confirmDepositRequest.sessionID,
        sessionInfo.copy(
          mintingBTCState = MintingBTCState.MintingBTCStateMinting
        )
      )
      _ <- checkIfAssetTokenMinted(
        sessionInfo.redeemAddress,
        confirmDepositRequest.sessionID,
        sessionInfo,
        sessionManager,
        utxoAlgebra
      )
    } yield ConfirmDepositResponse(txId, sessionInfo.redeemAddress).asRight[BridgeError]).recover {
      case e: BridgeError => Left(e)
      case e: Throwable =>
        e.printStackTrace()
        Left(InvalidInput("Error in confirmDeposit"))
    }
  }
}
