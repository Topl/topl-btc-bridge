package co.topl.bridge.modules

import cats.effect.IO
import org.bitcoins.core.protocol.transaction.Transaction
import co.topl.bridge.managers.SessionManagerAlgebra

trait BTCMonitorModule {

  def processTx(
      transaction: Transaction,
      sessionId: String,
      sessionManagerAlgebra: SessionManagerAlgebra[IO]
  ): IO[Unit] = {
    // get sessions where the status is waiting for BTC to arrive
    // check if transaction has one or several outputs that match the redeem address
    // transaction.outputs.toList.map(_.scriptPubKey.)
    for {
      _ <- IO.unit // sessionManagerAlgebra.updateSession(sessionId)
    } yield ()
  }

}
