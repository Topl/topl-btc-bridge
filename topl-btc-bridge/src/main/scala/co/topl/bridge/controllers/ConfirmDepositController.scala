package co.topl.bridge.controllers

import cats.Monad
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.bridge.managers.TransactionAlgebra
import co.topl.bridge.managers.WalletApiHelpers
import co.topl.shared.BridgeError
import co.topl.shared.ConfirmDepositRequest
import co.topl.shared.ConfirmDepositResponse
import com.google.protobuf.ByteString
import quivr.models.Int128
import quivr.models.KeyPair
import co.topl.shared.InvalidInput

class ConfirmDepositController[F[_]](
    psync: Sync[F],
    walletStateApi: WalletStateAlgebra[F],
    transactionBuilderApi: TransactionBuilderApi[F]
) extends WalletApiHelpers[F] {

  implicit val sync: cats.effect.kernel.Sync[F] = psync

  implicit val m: Monad[F] = psync

  val wsa: WalletStateAlgebra[F] = walletStateApi

  private def getCurrentAddress(
      fromFellowship: String,
      fromTemplate: String,
      someFromInteraction: Option[Int]
  ) = for {
    someCurrentIndices <- getCurrentIndices(
      fromFellowship,
      fromTemplate,
      someFromInteraction
    )
    predicateFundsToUnlock <- getPredicateFundsToUnlock(someCurrentIndices)
    fromAddress <- transactionBuilderApi.lockAddress(
      predicateFundsToUnlock.get
    )
  } yield fromAddress

  def confirmDeposit(
      keyPair: KeyPair,
      confirmDepositRequest: ConfirmDepositRequest,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      transactionAlgebra: TransactionAlgebra[F],
      utxoAlgebra: GenusQueryAlgebra[F],
      fee: Long
  ): F[Either[BridgeError, ConfirmDepositResponse]] = {
    import cats.implicits._
    val fromFellowship = "self"
    val fromTemplate = "default"
    println("confirmDeposit called")
    // import address codecs
    import co.topl.brambl.codecs.AddressCodecs.encodeAddress
    (for {
      currentAddress <- getCurrentAddress(
        fromFellowship,
        fromTemplate,
        None
      )
      _ = println(s"currentAddress: ${encodeAddress(currentAddress)}")
      txos <- utxoAlgebra.queryUtxo(
        currentAddress
      )
      _ = println(s"txos: $txos")
      groupTokenUtxo = txos.filter(_.transactionOutput.value.value.isGroup).head.outputAddress
      seriesTokenUtxo = txos.filter(_.transactionOutput.value.value.isSeries).head.outputAddress
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
      case e : Throwable => 
        e.printStackTrace()
        Left(InvalidInput("Error in confirmDeposit"))
    }
  }
}
