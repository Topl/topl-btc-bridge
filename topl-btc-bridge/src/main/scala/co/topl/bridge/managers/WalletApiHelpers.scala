package co.topl.bridge.managers

import co.topl.brambl.dataApi.WalletStateAlgebra
import cats.Monad
import co.topl.brambl.models.Indices
import co.topl.brambl.models.box.Lock
import co.topl.brambl.models.LockAddress
import co.topl.brambl.builders.TransactionBuilderApi

trait WalletApiHelpers[F[_]] {

  import cats.implicits._

  val wsa: WalletStateAlgebra[F]

  val tba: TransactionBuilderApi[F]

  implicit val m: Monad[F]

  def getCurrentIndices(
      fromFellowship: String,
      fromTemplate: String,
      someFromInteraction: Option[Int]
  ) = wsa.getCurrentIndicesForFunds(
    fromFellowship,
    fromTemplate,
    someFromInteraction
  )

  def getCurrentAddress(
      fromFellowship: String,
      fromTemplate: String,
      someFromInteraction: Option[Int]
  ): F[LockAddress] = for {
    someCurrentIndices <- getCurrentIndices(
      fromFellowship,
      fromTemplate,
      someFromInteraction
    )
    predicateFundsToUnlock <- getPredicateFundsToUnlock(someCurrentIndices)
    fromAddress <- tba.lockAddress(
      predicateFundsToUnlock.get
    )
  } yield fromAddress

  def getPredicateFundsToUnlock(someIndices: Option[Indices]) =
    someIndices
      .map(currentIndices => wsa.getLockByIndex(currentIndices))
      .sequence
      .map(_.flatten.map(Lock().withPredicate(_)))

  def getNextIndices(
      fromFellowship: String,
      fromTemplate: String
  ) =
    wsa.getNextIndicesForFunds(
      if (fromFellowship == "nofellowship") "self" else fromFellowship,
      if (fromFellowship == "nofellowship") "default"
      else fromTemplate
    )

  def getChangeLockPredicate(
      someNextIndices: Option[Indices],
      fromFellowship: String,
      fromTemplate: String
  ) =
    someNextIndices
      .map(idx =>
        wsa.getLock(
          if (fromFellowship == "nofellowship") "self" else fromFellowship,
          if (fromFellowship == "nofellowship") "default"
          else fromTemplate,
          idx.z
        )
      )
      .sequence
      .map(_.flatten)

}
