package co.topl.bridge.statemachine.pegin

import cats.Monad
import cats.effect.kernel.Async
import cats.implicits._
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.LockAddress
import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.bridge.managers.TransactionAlgebra
import co.topl.bridge.managers.WalletApiHelpers
import co.topl.genus.services.Txo
import com.google.protobuf.ByteString
import quivr.models.Int128
import quivr.models.KeyPair

class WaitingBTCOps[F[_]: Async](
    walletStateApi: WalletStateAlgebra[F],
    transactionBuilderApi: TransactionBuilderApi[F]
) extends WalletApiHelpers[F] {

  val m: Monad[F] = implicitly[Monad[F]]

  val wsa: WalletStateAlgebra[F] = walletStateApi

  val tba: TransactionBuilderApi[F] = transactionBuilderApi

  private def getGroupTokeUtxo(txos: Seq[Txo]) = {
    txos
      .filter(_.transactionOutput.value.value.isGroup)
      .head
      .outputAddress
  }

  private def getSeriesTokenUtxo(txos: Seq[Txo]) = {
    txos
      .filter(_.transactionOutput.value.value.isSeries)
      .head
      .outputAddress
  }

  private def computeAssetMintingStatement(
      amount: Long,
      currentAddress: LockAddress,
      utxoAlgebra: GenusQueryAlgebra[F]
  ) = for {
    txos <- utxoAlgebra.queryUtxo(
      currentAddress
    )
  } yield AssetMintingStatement(
    getGroupTokeUtxo(txos),
    getSeriesTokenUtxo(txos),
    Int128(
      ByteString.copyFrom(BigInt(amount).toByteArray)
    )
  )

  private def mintTBTC(
      redeemAddress: String,
      fromFellowship: String,
      fromTemplate: String,
      assetMintingStatement: AssetMintingStatement,
      keyPair: KeyPair,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      transactionAlgebra: TransactionAlgebra[F],
      fee: Long
  ) = for {
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
        redeemAddress
      )
    provedIoTx <- transactionAlgebra
      .proveSimpleTransactionFromParams(
        ioTransaction,
        keyPair
      )
      .flatMap(Async[F].fromEither(_))
    txId <- transactionAlgebra
      .broadcastSimpleTransactionFromParams(provedIoTx)
      .flatMap(Async[F].fromEither(_))
  } yield txId


  def startMintingProcess(
      fromFellowship: String,
      fromTemplate: String,
      redeemAddress: String,
      keyPair: KeyPair,
      amount: Long,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      transactionAlgebra: TransactionAlgebra[F],
      utxoAlgebra: GenusQueryAlgebra[F],
      fee: Long
  ): F[Unit] = {
    import cats.implicits._
    for {
      currentAddress <- getCurrentAddress(
        fromFellowship,
        fromTemplate,
        None
      )
      assetMintingStatement <- computeAssetMintingStatement(
        amount,
        currentAddress,
        utxoAlgebra
      )
      _ <- mintTBTC(
        redeemAddress,
        fromFellowship,
        fromTemplate,
        assetMintingStatement,
        keyPair,
        toplWalletAlgebra,
        transactionAlgebra,
        fee
      )
    } yield ()
  }

}
