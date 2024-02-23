package co.topl.bridge.controllers

import cats.effect.kernel.Sync
import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.bridge.managers.TransactionAlgebra
import co.topl.shared.ConfirmDepositRequest
import quivr.models.KeyPair
import quivr.models.Int128
import com.google.protobuf.ByteString
import co.topl.brambl.models.TransactionOutputAddress
import co.topl.brambl.utils.Encoding
import co.topl.brambl.models.TransactionId
import co.topl.brambl.constants.NetworkConstants
import co.topl.shared.InvalidBase58
import co.topl.shared.InvalidInput
import co.topl.shared.BridgeError
import co.topl.shared.ConfirmDepositResponse

object ConfirmDepositController {

  def confirmDeposit[F[_]: Sync](
      keyPair: KeyPair,
      networkId: Int,
      confirmDepositRequest: ConfirmDepositRequest,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      transactionAlgebra: TransactionAlgebra[F],
      fee: Long
  ): F[Either[BridgeError, ConfirmDepositResponse]] = {
    import cats.implicits._
    val fromFellowship = "self"
    val fromTemplate = "default"

    (for {
      _ <- Sync[F].fromEither(
        Either.cond(
          confirmDepositRequest.groupTokenUtxoIdx >= 0,
          (),
          new InvalidInput("groupTokenUtxoIdx must be greater than or equal to 0")
        )
      )
      _ <- Sync[F].fromEither(
        Either.cond(
          confirmDepositRequest.seriesTokenUtxoIdx >= 0,
          (),
          new InvalidInput("seriesTokenUtxoIdx must be greater than or equal to 0")
        )
      )
      groupTokenUtxoBytes <- Sync[F].fromEither(
        Encoding
          .decodeFromBase58(confirmDepositRequest.groupTokenUtxoTxId)
          .left
          .map(_ => InvalidBase58("groupTokenUtxoTxId is not valid base58"))
      )
      groupTokenUtxo = TransactionOutputAddress(
        networkId,
        NetworkConstants.MAIN_LEDGER_ID,
        confirmDepositRequest.groupTokenUtxoIdx,
        TransactionId(ByteString.copyFrom(groupTokenUtxoBytes))
      )
      seriesTokenUtxoBytes <- Sync[F].fromEither(
        Encoding
          .decodeFromBase58(confirmDepositRequest.seriesTokenUtxoTxId)
          .left
          .map(_ => InvalidBase58("seriesTokenUtxoTxId is not valid base58"))
      )
      seriesTokenUtxo = TransactionOutputAddress(
        networkId,
        NetworkConstants.MAIN_LEDGER_ID,
        confirmDepositRequest.seriesTokenUtxoIdx,
        TransactionId(ByteString.copyFrom(seriesTokenUtxoBytes))
      )
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
          assetMintingStatement
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
    } yield ConfirmDepositResponse(txId).asRight[BridgeError]).recover {
      case e: BridgeError => Left(e)
    }
  }
}
