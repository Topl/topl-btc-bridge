package co.topl.bridge.modules

import cats.Monad
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.Event
import co.topl.brambl.models.TransactionOutputAddress
import co.topl.brambl.syntax._
import co.topl.brambl.utils.Encoding
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.Fellowship
import co.topl.bridge.SystemGlobalState
import co.topl.bridge.Template
import co.topl.bridge.managers.TransactionAlgebra
import co.topl.bridge.managers.WalletApiHelpers
import co.topl.genus.services.Txo
import org.typelevel.log4cats.Logger
import quivr.models.Int128
import quivr.models.KeyPair

import scala.concurrent.duration._

class InitializationModule[F[_]: Async: Logger](
    val wa: WalletApi[F],
    val keyPair: KeyPair,
    genusQueryAlgebra: GenusQueryAlgebra[F],
    transactionAlgebra: TransactionAlgebra[F],
    currentState: Ref[F, SystemGlobalState]
)(implicit val tba: TransactionBuilderApi[F], val wsa: WalletStateAlgebra[F])
    extends GroupMintingOps[F]
    with SeriesMintingOps[F] {

  import WalletApiHelpers._

  import org.typelevel.log4cats.syntax._

  import cats.implicits._

  val sync: cats.effect.kernel.Async[F] = implicitly[Async[F]]

  val m: Monad[F] = implicitly[Monad[F]]

  private def getTxos(
      fromFellowship: Fellowship,
      fromTemplate: Template
  ): F[Seq[Txo]] = for {
    currentAddress <- getCurrentAddress[F](
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

  def checkForLvls(
      fromFellowship: Fellowship,
      fromTemplate: Template
  ): F[Unit] = (for {
    _ <- info"Checking for LVLs"
    currentAddress <- wsa.getCurrentAddress
    txos <- getTxos(fromFellowship, fromTemplate)
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
              currentError = Some(
                s"No LVLs found. Please fund the bridge wallet: $currentAddress"
              ),
              isReady = false
            )
          ) >>
          Async[F].pure(false)
      }
    _ <-
      if (!hasLvls)
        Async[F].sleep(5.second) >> checkForLvls(fromFellowship, fromTemplate)
      else Async[F].unit
  } yield ()).handleErrorWith { e =>
    e.printStackTrace()
    error"Error checking LVLs: $e" >>
      error"Retrying in 5 seconds" >>
      Async[F].sleep(
        5.second
      ) >> checkForLvls(fromFellowship, fromTemplate)
  }

  private def checkIfGroupTokenMinted(
      fromFellowship: Fellowship,
      fromTemplate: Template
  ): F[Unit] = for {
    newTxos <- getTxos(fromFellowship, fromTemplate)
    _ <-
      if (newTxos.filter(_.transactionOutput.value.value.isGroup).nonEmpty) {
        info"Group Token minted successfully" >> currentState
          .update(
            _.copy(
              currentStatus = Some("Group Token minted"),
              currentError = None,
              isReady = false
            )
          )
      } else {
        info"Group Token not minted, checking txos in 5 seconds" >> currentState
          .update(
            _.copy(
              currentStatus = Some("Waiting for group tokens..."),
              currentError = None,
              isReady = false
            )
          ) >> Async[F].sleep(5.second) >> checkIfGroupTokenMinted(
          fromFellowship,
          fromTemplate
        )
      }
  } yield ()

  def checkIfSeriesTokenMinted(
      fromFellowship: Fellowship,
      fromTemplate: Template
  ): F[Unit] = for {
    newTxos <- getTxos(fromFellowship, fromTemplate)
    _ <-
      if (newTxos.filter(_.transactionOutput.value.value.isSeries).nonEmpty) {
        info"Series Token minted successfully" >> currentState
          .update(
            _.copy(
              currentStatus = Some("Series Token minted"),
              currentError = None,
              isReady = true
            )
          )
      } else {
        info"Series Token not minted, checking txos in 5 seconds" >> currentState
          .update(
            _.copy(
              currentStatus = Some("Waiting for series tokens..."),
              currentError = None,
              isReady = false
            )
          ) >> Async[F].sleep(5.second) >> checkIfSeriesTokenMinted(
          fromFellowship,
          fromTemplate
        )
      }
  } yield ()

  def mintGroupToken(
      fromFellowship: Fellowship,
      fromTemplate: Template
  ): F[Unit] = (for {
    _ <- info"Minting Group Token"
    txos <- getTxos(fromFellowship, fromTemplate)
    lockAddress <- wsa.getCurrentAddress
    someLock <- wsa.getLockByAddress(lockAddress)
    _ = assert(
      someLock.isDefined,
      "Indices not found while minting group token"
    )
    _ = assert(
      txos.filter(_.transactionOutput.value.value.isLvl).nonEmpty,
      "No LVLs found while minting group token"
    )
    someChangeIdx <- wsa.getNextIndicesForFunds(
      fromFellowship.underlying,
      fromTemplate.underlying
    )
    _ = assert(
      someChangeIdx.isDefined,
      "Change lock not found while minting group token"
    )
    someChangeLock <- getChangeLockPredicate[F](
      someChangeIdx,
      fromFellowship,
      fromTemplate
    )
    ioTx <- buildGroupTx(
      txos.filter(_.transactionOutput.value.value.isLvl),
      txos.filterNot(_.transactionOutput.value.value.isLvl),
      someLock.get,
      1,
      10,
      someChangeIdx,
      keyPair,
      Event.GroupPolicy(
        "tBTC",
        txos
          .filter(_.transactionOutput.value.value.isLvl)
          .map(x =>
            TransactionOutputAddress(
              x.outputAddress.network,
              x.outputAddress.ledger,
              x.outputAddress.index,
              x.outputAddress.id
            )
          )
          .head,
        None
      ),
      someChangeLock
    )
    provedTx <- transactionAlgebra.proveSimpleTransactionFromParams(
      ioTx,
      keyPair
    )
    _ = assert(
      provedTx.isRight,
      "Error proving transaction while minting group token"
    )
    eitherSentTx <- transactionAlgebra.broadcastSimpleTransactionFromParams(
      provedTx.toOption.get
    )
    _ <- Async[F].fromEither(eitherSentTx) // if this fails we should retry
    _ <- checkIfGroupTokenMinted(fromFellowship, fromTemplate)

  } yield ()).handleErrorWith { e =>
    e.printStackTrace()
    error"Error setting up wallet: ${e}" >>
      error"Retrying in 5 seconds" >>
      Async[F].sleep(
        5.second
      ) >> mintGroupToken(fromFellowship, fromTemplate)
  }

  def mintSeriesToken(
      fromFellowship: Fellowship,
      fromTemplate: Template
  ): F[Unit] = (for {
    _ <- info"Minting Series Token"
    txos <- getTxos(fromFellowship, fromTemplate)
    lockAddress <- wsa.getCurrentAddress
    someLock <- wsa.getLockByAddress(lockAddress)
    _ = assert(
      someLock.isDefined,
      "Indices not found while minting series token"
    )
    _ = assert(
      txos.filter(_.transactionOutput.value.value.isGroup).nonEmpty,
      "No Group Tokens found while minting series token"
    )
    someChangeIdx <- wsa.getNextIndicesForFunds(
      fromFellowship.underlying,
      fromTemplate.underlying
    )
    _ = assert(
      someChangeIdx.isDefined,
      "Change lock not found while minting series token"
    )
    someChangeLock <- getChangeLockPredicate[F](
      someChangeIdx,
      fromFellowship,
      fromTemplate
    )
    ioTx <- buildSeriesTx(
      txos.filter(_.transactionOutput.value.value.isGroup),
      txos.filterNot(_.transactionOutput.value.value.isGroup),
      someLock.get,
      1L,
      10L,
      someChangeIdx,
      keyPair,
      Event.SeriesPolicy(
        "tBTC Series",
        None,
        txos
          .filter(_.transactionOutput.value.value.isLvl)
          .map(x =>
            TransactionOutputAddress(
              x.outputAddress.network,
              x.outputAddress.ledger,
              x.outputAddress.index,
              x.outputAddress.id
            )
          )
          .head
      ),
      someChangeLock
    )
    provedTx <- transactionAlgebra.proveSimpleTransactionFromParams(
      ioTx,
      keyPair
    )
    _ = assert(
      provedTx.isRight,
      "Error proving transaction while minting series token"
    )
    eitherSentTx <- transactionAlgebra.broadcastSimpleTransactionFromParams(
      provedTx.toOption.get
    )
    _ <- Async[F].fromEither(eitherSentTx) // if this fails we should retry
    _ <- checkIfSeriesTokenMinted(fromFellowship, fromTemplate)

  } yield ()).handleErrorWith { e =>
    e.printStackTrace
    error"Error setting up wallet: ${e}" >>
      error"Retrying in 5 seconds" >>
      Async[F].sleep(
        5.second
      ) >> mintSeriesToken(fromFellowship, fromTemplate)
  }

  def checkForGroupToken(
      fromFellowship: Fellowship,
      fromTemplate: Template
  ): F[Boolean] = (
    for {
      _ <- info"Checking for Group Tokens"
      txos <- getTxos(fromFellowship, fromTemplate)
      groupTxos = txos.filter(_.transactionOutput.value.value.isGroup)
      hasGroupToken <-
        if (groupTxos.nonEmpty) {
          (info"Found Group Tokens" >> currentState
            .update(
              _.copy(
                currentStatus = Some(
                  s"Group token found: ${Encoding.encodeToHex(groupTxos.head.transactionOutput.value.value.group.get.groupId.value.toByteArray())}"
                ),
                currentError = None,
                isReady = false
              )
            ) >>
            Async[F].pure(true))
        } else {
          info"No Group Token found. Preparing to mint tokens." >> currentState
            .update(
              _.copy(
                currentStatus = Some("Preparing to mint group tokens..."),
                currentError = None,
                isReady = false
              )
            ) >>
            Async[F].pure(false)
        }
    } yield hasGroupToken
  )

  def checkForSeriesToken(
      fromFellowship: Fellowship,
      fromTemplate: Template
  ): F[Boolean] = (
    for {
      _ <- info"Checking for Series Tokens"
      txos <- getTxos(fromFellowship, fromTemplate)
      hasSeriesToken <-
        if (txos.filter(_.transactionOutput.value.value.isSeries).nonEmpty) {
          (info"Found Series Tokens: ${int128AsBigInt(sumLvls(txos))}" >> currentState
            .update(
              _.copy(
                currentStatus = Some("Series token found"),
                currentError = None,
                isReady = false
              )
            ) >>
            Async[F].pure(true))
        } else {
          info"No Series Token found. Preparing to mint tokens." >> currentState
            .update(
              _.copy(
                currentStatus = Some("Preparing to mint series tokens..."),
                currentError = None,
                isReady = false
              )
            ) >>
            Async[F].pure(false)
        }
    } yield hasSeriesToken
  )

  def setupWallet(
      fromFellowship: Fellowship,
      fromTemplate: Template
  ): F[Unit] = {
    (for {
      _ <- checkForLvls(fromFellowship, fromTemplate)
      hasGroupToken <- checkForGroupToken(fromFellowship, fromTemplate)
      _ <-
        if (!hasGroupToken) mintGroupToken(fromFellowship, fromTemplate)
        else Async[F].unit
      hasSeriesToken <- checkForSeriesToken(fromFellowship, fromTemplate)
      _ <-
        if (!hasSeriesToken) mintSeriesToken(fromFellowship, fromTemplate)
        else Async[F].unit
    } yield ()).handleErrorWith { e =>
      e.printStackTrace
      error"Error setting up wallet: $e" >>
        error"Retrying in 5 seconds" >>
        Async[F].sleep(
          5.second
        ) >> setupWallet(fromFellowship, fromTemplate)
    }
  }

}
