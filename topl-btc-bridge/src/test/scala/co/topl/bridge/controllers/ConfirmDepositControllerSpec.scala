package co.topl.bridge.controllers

import cats.effect.IO
import co.topl.brambl.cli.mockbase.BaseWalletStateAlgebra
import co.topl.bridge.stubs.BaseToplWalletAlgebra
import co.topl.bridge.stubs.BaseTransactionAlgebra
import co.topl.shared.ConfirmDepositRequest
import munit.CatsEffectSuite
import co.topl.brambl.cli.mockbase.BaseTransactionBuilderApi
import co.topl.bridge.stubs.UnitTestStubs
import co.topl.brambl.models.Indices
import co.topl.brambl.models.box.Lock
import co.topl.brambl.models.LockAddress

class ConfirmDepositControllerSpec extends CatsEffectSuite with SharedData {

  import co.topl.bridge.stubs.UnitTestStubs._

  val transactionBuilderApi = new BaseTransactionBuilderApi[IO]() {
    override def lockAddress(lock: Lock): IO[LockAddress] = IO(lockAddress01)
  }

  val walletStateAlgebra = new BaseWalletStateAlgebra[IO]() {
    override def getCurrentIndicesForFunds(
        fellowship: String,
        template: String,
        someInteraction: Option[Int]
    ): IO[Option[Indices]] = IO.pure(Some(Indices(1, 1, 1)))

    override def getLockByAddress(
        lockAddress: String
    ): IO[Option[Lock.Predicate]] = IO.pure(Some(lock01))

    override def getLockByIndex(indices: Indices): IO[Option[Lock.Predicate]] =
      IO.pure(Some(lock01))

  }

  val confirmDepositController = new ConfirmDepositController[IO](
    IO.asyncForIO,
    walletStateAlgebra,
    transactionBuilderApi
  )

  test(
    "ConfirmDepositController should succeed on valid input"
  ) {
    assertIOBoolean(
      for {
        keyPair <- walletManagementUtils.loadKeys(
          toplWalletFile,
          testToplPassword
        )
        res <- confirmDepositController.confirmDeposit(
          keyPair,
          ConfirmDepositRequest(
            "sessionID",
            1000L
          ),
          new BaseToplWalletAlgebra[IO](),
          new BaseTransactionAlgebra[IO](),
          UnitTestStubs.makeGenusQueryAlgebraMockWithAddress[IO],
          10L
        )
      } yield res.isRight
    )
  }

}
