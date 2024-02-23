package co.topl.bridge.stubs

import co.topl.brambl.models.transaction.IoTransaction
import co.topl.bridge.managers.SimpleTransactionAlgebraError
import co.topl.bridge.managers.TransactionAlgebra
import quivr.models.KeyPair
import cats.Monad

class BaseTransactionAlgebra[F[_]: Monad] extends TransactionAlgebra[F] {

  import UnitTestStubs._
  import cats.implicits._

  override def proveSimpleTransactionFromParams(
      ioTransaction: IoTransaction,
      keyPair: KeyPair
  ): F[Either[SimpleTransactionAlgebraError, IoTransaction]] =
    iotransaction01.asRight.pure[F]

  override def broadcastSimpleTransactionFromParams(
      provedTransaction: IoTransaction
  ): F[Either[SimpleTransactionAlgebraError, String]] = "TXID".asRight.pure[F]

}
