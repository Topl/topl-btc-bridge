package co.topl.bridge

import cats.effect.kernel.Sync
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.Event
import co.topl.brambl.models.Indices
import co.topl.brambl.models.LockAddress
import co.topl.brambl.models.box.Lock
import co.topl.brambl.utils.Encoding
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.managers.CreateTxError
import co.topl.genus.services.Txo
import quivr.models.KeyPair

import TransactionBuilderApi.implicits._
import co.topl.brambl.models.transaction.IoTransaction

trait SeriesMintingOps[G[_]] extends CommonTxOps {

  import cats.implicits._

  implicit val sync: Sync[G]

  val tba: TransactionBuilderApi[G]

  val wsa: WalletStateAlgebra[G]

  val wa: WalletApi[G]

  private def buildSeriesTransaction(
      txos: Seq[Txo],
      predicateFundsToUnlock: Lock.Predicate,
      lockForChange: Lock,
      recipientLockAddress: LockAddress,
      amount: Long,
      fee: Long,
      someNextIndices: Option[Indices],
      keyPair: KeyPair,
      seriesPolicy: Event.SeriesPolicy
  ): G[IoTransaction] =
    for {
      changeAddress <- tba.lockAddress(
        lockForChange
      )
      eitherIoTransaction <- tba.buildSeriesMintingTransaction(
        txos,
        predicateFundsToUnlock,
        seriesPolicy,
        amount,
        recipientLockAddress,
        changeAddress,
        fee
      )
      ioTransaction <- Sync[G].fromEither(eitherIoTransaction)
      // Only save to wallet interaction if there is a change output in the transaction
      _ <-
        if (ioTransaction.outputs.length >= 2) for {
          vk <- someNextIndices
            .map(nextIndices =>
              wa
                .deriveChildKeys(keyPair, nextIndices)
                .map(_.vk)
            )
            .sequence
          _ <- wsa.updateWalletState(
            Encoding.encodeToBase58Check(
              lockForChange.getPredicate.toByteArray
            ),
            changeAddress.toBase58(),
            vk.map(_ => "ExtendedEd25519"),
            vk.map(x => Encoding.encodeToBase58(x.toByteArray)),
            someNextIndices.get
          )
        } yield ioTransaction
        else {
          Sync[G].delay(ioTransaction)
        }
    } yield ioTransaction

  def buildSeriesTx(
      lvlTxos: Seq[Txo],
      nonLvlTxos: Seq[Txo],
      predicateFundsToUnlock: Lock.Predicate,
      amount: Long,
      fee: Long,
      someNextIndices: Option[Indices],
      keyPair: KeyPair,
      seriesPolicy: Event.SeriesPolicy,
      changeLock: Option[Lock]
  ): G[IoTransaction] = (if (lvlTxos.isEmpty) {
                           Sync[G].raiseError(
                             CreateTxError("No LVL txos found")
                           )
                         } else {
                           changeLock match {
                             case Some(lockPredicateForChange) =>
                               tba
                                 .lockAddress(lockPredicateForChange)
                                 .flatMap { changeAddress =>
                                   buildSeriesTransaction(
                                     lvlTxos ++ nonLvlTxos,
                                     predicateFundsToUnlock,
                                     lockPredicateForChange,
                                     changeAddress,
                                     amount,
                                     fee,
                                     someNextIndices,
                                     keyPair,
                                     seriesPolicy
                                   )
                                 }
                             case None =>
                               Sync[G].raiseError(
                                 CreateTxError("Unable to generate change lock")
                               )
                           }
                         })
}
