package co.topl.bridge.managers

import cats.Monad
import cats.effect.kernel.Sync
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.brambl.models.transaction.IoTransaction
import co.topl.brambl.wallet.WalletApi
import co.topl.genus.services.Txo
import com.google.protobuf.ByteString
import io.circe.Json
import quivr.models.KeyPair

trait ToplWalletAlgebra[F[_]] {

  def createSimpleAssetMintingTransactionFromParams(
      keyPair: KeyPair,
      fromFellowship: String,
      fromTemplate: String,
      someFromInteraction: Option[Int],
      fee: Long,
      ephemeralMetadata: Option[Json],
      commitment: Option[ByteString],
      assetMintingStatement: AssetMintingStatement
  ): F[IoTransaction]

  def getCurrentPubKeyAndPrepareNext(): F[(Int, String)]

}

object ToplWalletImpl {
  import cats.implicits._

  def make[F[_]](
      psync: Sync[F],
      walletApi: WalletApi[F],
      walletStateApi: WalletStateAlgebra[F],
      transactionBuilderApi: TransactionBuilderApi[F],
      utxoAlgebra: GenusQueryAlgebra[F]
  ): ToplWalletAlgebra[F] = new ToplWalletAlgebra[F]
    with WalletApiHelpers[F]
    with AssetMintingOps[F] {

    override implicit val sync: cats.effect.kernel.Sync[F] = psync

    implicit val m: Monad[F] = sync

    val wsa: WalletStateAlgebra[F] = walletStateApi

    val tba = transactionBuilderApi

    val wa = walletApi

    def getCurrentPubKeyAndPrepareNext(): F[(Int, String)] = {
      for {
        someIndex <- walletStateApi.getCurrentIndicesForFunds(
          "self",
          "default",
          None
        )
      } yield ???
    }
    // {
    //   for {
    //     idx <- currentIdx.getAndUpdate(_ + 1)
    //     pubKey <- KeyGenerationUtils.generateKey(km, idx)
    //   } yield (idx, pubKey)
    // }

    private def sharedOps(
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
      someNextIndices <- getNextIndices(fromFellowship, fromTemplate)
      changeLock <- getChangeLockPredicate(
        someNextIndices,
        fromFellowship,
        fromTemplate
      )
    } yield (
      predicateFundsToUnlock.get,
      someCurrentIndices,
      someNextIndices,
      changeLock
    )

    def createSimpleAssetMintingTransactionFromParams(
        keyPair: KeyPair,
        fromFellowship: String,
        fromTemplate: String,
        someFromInteraction: Option[Int],
        fee: Long,
        ephemeralMetadata: Option[Json],
        commitment: Option[ByteString],
        assetMintingStatement: AssetMintingStatement
    ): F[IoTransaction] = for {
      tuple <- sharedOps(
        fromFellowship,
        fromTemplate,
        someFromInteraction
      )
      (
        predicateFundsToUnlock,
        someCurrentIndices,
        someNextIndices,
        changeLock
      ) = tuple
      fromAddress <- transactionBuilderApi.lockAddress(
        predicateFundsToUnlock
      )
      response <- utxoAlgebra
        .queryUtxo(fromAddress)
        .attempt
        .flatMap {
          _ match {
            case Left(_) =>
              Sync[F].raiseError(
                CreateTxError("Problem contacting network")
              ): F[Seq[Txo]]
            case Right(txos) => Sync[F].pure(txos: Seq[Txo])
          }
        }
      lvlTxos = response.filter(
        _.transactionOutput.value.value.isLvl
      )
      nonLvlTxos = response.filter(x =>
        (
          !x.transactionOutput.value.value.isLvl &&
            x.outputAddress != assetMintingStatement.groupTokenUtxo &&
            x.outputAddress != assetMintingStatement.seriesTokenUtxo
        )
      )
      groupTxo <- response
        .filter(
          _.transactionOutput.value.value.isGroup
        )
        .find(_.outputAddress == assetMintingStatement.groupTokenUtxo)
        .map(Sync[F].delay(_))
        .getOrElse(
          Sync[F].raiseError(
            new Exception(
              "Group token utxo not found"
            )
          )
        )
      seriesTxo <- response
        .filter(
          _.transactionOutput.value.value.isSeries
        )
        .find(_.outputAddress == assetMintingStatement.seriesTokenUtxo)
        .map(Sync[F].delay(_))
        .getOrElse(
          Sync[F].raiseError(
            new Exception(
              "Series token utxo not found"
            )
          )
        )
      ioTransaction <- buildAssetTxAux(
        keyPair,
        lvlTxos,
        nonLvlTxos,
        groupTxo,
        seriesTxo,
        fromAddress,
        predicateFundsToUnlock.getPredicate,
        fee,
        someNextIndices,
        assetMintingStatement,
        ephemeralMetadata,
        commitment,
        changeLock
      )
    } yield ioTransaction
  }

}
