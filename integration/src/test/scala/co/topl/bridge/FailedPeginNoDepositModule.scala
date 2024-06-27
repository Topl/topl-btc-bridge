package co.topl.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

trait FailedPeginNoDepositModule {

  self: BridgeIntegrationSpec =>

  def failedPeginNoDeposit(): IO[Unit] = {

    assertIO(
      for {
        newAddress <- getNewAddress
        startSessionResponse <- startSession(1)
        _ <- generateToAddress(1, 102, newAddress)
        _ <- checkStatus(startSessionResponse.sessionID)
          .flatMap(x =>
            generateToAddress(1, 1, newAddress) >> IO
              .sleep(5.second) >> IO.pure(x)
          )
          .iterateUntil(
            _.code == 404
          )
        _ <-
          info"Session ${startSessionResponse.sessionID} was successfully removed"
      } yield (),
      ()
    )
  }
}
