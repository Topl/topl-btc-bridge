package co.topl.bridge.managers

import co.topl.brambl.dataApi.WalletStateAlgebra
import cats.Monad
import co.topl.brambl.models.Indices
import co.topl.brambl.models.box.Lock
import co.topl.brambl.models.LockAddress
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.bridge.Fellowship
import co.topl.bridge.Template

trait WalletApiHelpers[F[_]] {

  import cats.implicits._

  val wsa: WalletStateAlgebra[F]

  val tba: TransactionBuilderApi[F]

  implicit val m: Monad[F]

  def getCurrentIndices(
      fromFellowship: Fellowship,
      fromTemplate: Template,
      someFromInteraction: Option[Int]
  ) = wsa.getCurrentIndicesForFunds(
    fromFellowship.underlying,
    fromTemplate.underlying,
    someFromInteraction
  )

  def getCurrentAddress(
      fromFellowship: Fellowship,
      fromTemplate: Template,
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
      fromFellowship: Fellowship,
      fromTemplate: Template
  ) =
    wsa.getNextIndicesForFunds(
      if (fromFellowship.underlying == "nofellowship") "self"
      else fromFellowship.underlying,
      if (fromFellowship.underlying == "nofellowship") "default"
      else fromTemplate.underlying
    )

  def getChangeLockPredicate(
      someNextIndices: Option[Indices],
      fromFellowship: Fellowship,
      fromTemplate: Template
  ) =
    someNextIndices
      .map(idx =>
        wsa.getLock(
          if (fromFellowship.underlying == "nofellowship") "self"
          else fromFellowship.underlying,
          if (fromFellowship.underlying == "nofellowship") "default"
          else fromTemplate.underlying,
          idx.z
        )
      )
      .sequence
      .map(_.flatten)

}
