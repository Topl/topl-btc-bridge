package co.topl.bridge

import cats.Monad
import cats.effect.kernel.Async
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.managers.WalletApiHelpers
import co.topl.genus.services.Txo
import org.typelevel.log4cats.Logger
import quivr.models.Int128

import scala.concurrent.duration._
import co.topl.brambl.syntax._
import cats.effect.kernel.Ref

class InitializationModule[F[_]: Async: Logger](
    // val psync: Async[F],
    val tba: TransactionBuilderApi[F],
    val wsa: WalletStateAlgebra[F],
    val wa: WalletApi[F],
    genusQueryAlgebra: GenusQueryAlgebra[F],
    currentState: Ref[F, SystemGlobalState]
) extends GroupMintingOps[F]
    with WalletApiHelpers[F] {

  import org.typelevel.log4cats.syntax._

  import cats.implicits._

  val sync: cats.effect.kernel.Async[F] = implicitly[Async[F]]

  val m: Monad[F] = implicitly[Monad[F]]

  val fromFellowship = "self"
  val fromTemplate = "default"

  private def checkGroupToken(): F[Boolean] = for {
    currentAddress <- getCurrentAddress(
      fromFellowship,
      fromTemplate,
      None
    )
    txos <- genusQueryAlgebra.queryUtxo(
      currentAddress
    )
  } yield txos
    .filter(_.transactionOutput.value.value.isGroup)
    .nonEmpty

  private def checkSeriesToken(): F[Boolean] = for {
    currentAddress <- getCurrentAddress(
      fromFellowship,
      fromTemplate,
      None
    )
    txos <- genusQueryAlgebra.queryUtxo(
      currentAddress
    )
  } yield txos
    .filter(_.transactionOutput.value.value.isSeries)
    .nonEmpty

  private def getTxos(): F[Seq[Txo]] = for {
    currentAddress <- getCurrentAddress(
      fromFellowship,
      fromTemplate,
      None
    )
    txos <- genusQueryAlgebra.queryUtxo(
      currentAddress
    )
  } yield txos

  private def sumLvls(txos: Seq[Txo]): Int128 = {
    txos
      .map(
        _.transactionOutput.value.value.lvl
          .map(_.quantity)
          .getOrElse(longAsInt128(0))
      )
      .fold(longAsInt128(0))(_ + _)
  }

  def checkForLvls(): F[Unit] = (for {
    _ <- info"Checking for LVLs"
    txos <- getTxos()
    hasLvls <-
      if (txos.filter(_.transactionOutput.value.value.isLvl).nonEmpty) {
        (info"Found LVLs: ${int128AsBigInt(sumLvls(txos))}" >> currentState
          .update(
            _.copy(
              currentStatus = Some("LVLs found"),
              currentError = None,
              isReady = false
            )
          ) >>
          Async[F].pure(true))
      } else {
        warn"No LVLs found. Please fund the bridge wallet." >> currentState
          .update(
            _.copy(
              currentStatus = Some("Checking wallet..."),
              currentError =
                Some("No LVLs found. Please fund the bridge wallet."),
              isReady = false
            )
          )
        Async[F].pure(false)
      }
    _ <-
      if (!hasLvls)
        Async[F].sleep(5.second) >> setupWallet()
      else Async[F].unit
  } yield ()).handleErrorWith { e =>
    error"Error checking LVLs: $e" >>
      error"Retrying in 5 seconds" >>
      Async[F].sleep(
        5.second
      ) >> setupWallet()
  }

  def setupWallet(): F[Unit] = {
    (for {
      _ <- checkForLvls()
    } yield ()).handleErrorWith { e =>
      error"Error setting up wallet: $e" >>
        error"Retrying in 5 seconds" >>
        Async[F].sleep(
          5.second
        ) >> setupWallet()
    }
  }

}
