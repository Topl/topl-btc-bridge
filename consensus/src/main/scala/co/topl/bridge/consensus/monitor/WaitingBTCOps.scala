package co.topl.bridge.consensus.monitor

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.implicits._
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.LockAddress
import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.Fellowship
import co.topl.bridge.consensus.Lvl
import co.topl.bridge.consensus.Template
import co.topl.bridge.consensus.managers.ToplWalletAlgebra
import co.topl.bridge.consensus.managers.TransactionAlgebra
import co.topl.bridge.consensus.managers.WalletApiHelpers
import co.topl.genus.services.Txo
import io.grpc.ManagedChannel
import org.typelevel.log4cats.Logger
import quivr.models.Int128
import quivr.models.KeyPair

object WaitingBTCOps {

  import WalletApiHelpers._
  import ToplWalletAlgebra._
  import TransactionAlgebra._

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

  private def computeAssetMintingStatement[F[_]: Async: Logger](
      amount: Int128,
      currentAddress: LockAddress,
      utxoAlgebra: GenusQueryAlgebra[F]
  ) = for {
    txos <- (utxoAlgebra
      .queryUtxo(
        currentAddress
      )
      .attempt >>= {
      case Left(e) =>
        import scala.concurrent.duration._
        Async[F].sleep(5.second) >> Async[F].pure(e.asLeft[Seq[Txo]])
      case Right(txos) =>
        if (txos.isEmpty) {
          import scala.concurrent.duration._
          import org.typelevel.log4cats.syntax._
          Async[F].sleep(
            5.second
          ) >> warn"No UTXO found while minting" >> Async[F].pure(
            new Throwable("No UTXOs found").asLeft[Seq[Txo]]
          )
        } else
          Async[F].pure(txos.asRight[Throwable])
    })
      .iterateUntil(_.isRight)
      .map(_.toOption.get)
  } yield AssetMintingStatement(
    getGroupTokeUtxo(txos),
    getSeriesTokenUtxo(txos),
    amount
  )

  private def mintTBTC[F[_]: Async](
      redeemAddress: String,
      fromFellowship: Fellowship,
      fromTemplate: Template,
      assetMintingStatement: AssetMintingStatement,
      keyPair: KeyPair,
      fee: Lvl
  )(implicit
      tba: TransactionBuilderApi[F],
      walletApi: WalletApi[F],
      wsa: WalletStateAlgebra[F],
      utxoAlgebra: GenusQueryAlgebra[F],
      channelResource: Resource[F, ManagedChannel]
  ) = for {
    ioTransaction <- createSimpleAssetMintingTransactionFromParams(
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
    provedIoTx <- proveSimpleTransactionFromParams(
      ioTransaction,
      keyPair
    )
      .flatMap(Async[F].fromEither(_))
    txId <- broadcastSimpleTransactionFromParams(provedIoTx)
      .flatMap(Async[F].fromEither(_))
  } yield txId

  def startMintingProcess[F[_]: Async: Logger](
      fromFellowship: Fellowship,
      fromTemplate: Template,
      redeemAddress: String,
      amount: Int128
  )(implicit
      keyPair: KeyPair,
      walletApi: WalletApi[F],
      walletStateApi: WalletStateAlgebra[F],
      transactionBuilderApi: TransactionBuilderApi[F],
      utxoAlgebra: GenusQueryAlgebra[F],
      channelResource: Resource[F, ManagedChannel],
      defaultMintingFee: Lvl
  ): F[Unit] = {
    import cats.implicits._
    for {
      currentAddress <- getCurrentAddress[F](
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
        defaultMintingFee
      )
    } yield ()
  }

}
