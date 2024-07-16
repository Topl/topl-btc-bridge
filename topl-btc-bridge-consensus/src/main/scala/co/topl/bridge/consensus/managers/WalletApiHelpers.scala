package co.topl.bridge.consensus.managers

import co.topl.brambl.dataApi.WalletStateAlgebra
import cats.Monad
import co.topl.brambl.models.Indices
import co.topl.brambl.models.box.Lock
import co.topl.brambl.models.LockAddress
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.bridge.consensus.Fellowship
import co.topl.bridge.consensus.Template

object WalletApiHelpers {


  def getCurrentIndices[F[_]](
      fromFellowship: Fellowship,
      fromTemplate: Template,
      someFromInteraction: Option[Int]
  )(implicit wsa: WalletStateAlgebra[F]) = wsa.getCurrentIndicesForFunds(
    fromFellowship.underlying,
    fromTemplate.underlying,
    someFromInteraction
  )

  def getCurrentAddress[F[_]: Monad](
      fromFellowship: Fellowship,
      fromTemplate: Template,
      someFromInteraction: Option[Int]
  )(implicit
      wsa: WalletStateAlgebra[F],
      tba: TransactionBuilderApi[F]
  ): F[LockAddress] = {
    import cats.implicits._
    for {
      someCurrentIndices <- getCurrentIndices(
        fromFellowship,
        fromTemplate,
        someFromInteraction
      )
      predicateFundsToUnlock <- getPredicateFundsToUnlock[F](someCurrentIndices)
      fromAddress <- tba.lockAddress(
        predicateFundsToUnlock.get
      )
    } yield fromAddress
  }

  def getPredicateFundsToUnlock[F[_]: Monad](
      someIndices: Option[Indices]
  )(implicit wsa: WalletStateAlgebra[F]) = {
    import cats.implicits._
    someIndices
      .map(currentIndices => wsa.getLockByIndex(currentIndices))
      .sequence
      .map(_.flatten.map(Lock().withPredicate(_)))
  }

  def getNextIndices[F[_]](
      fromFellowship: Fellowship,
      fromTemplate: Template
  )(implicit wsa: WalletStateAlgebra[F]) =
    wsa.getNextIndicesForFunds(
      if (fromFellowship.underlying == "nofellowship") "self"
      else fromFellowship.underlying,
      if (fromFellowship.underlying == "nofellowship") "default"
      else fromTemplate.underlying
    )

  def getChangeLockPredicate[F[_]: Monad](
      someNextIndices: Option[Indices],
      fromFellowship: Fellowship,
      fromTemplate: Template
  )(implicit wsa: WalletStateAlgebra[F]) = {
    import cats.implicits._
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

}
