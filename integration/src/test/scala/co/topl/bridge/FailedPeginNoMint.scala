package co.topl.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

trait FailedPeginNoMintModule {

  self: BridgeIntegrationSpec =>

  def failedPeginNoMint(): IO[Unit] = {

    assertIO(
      for {
        newAddress <- getNewAddress
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession
        bitcoinTx <- createTx(
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx)
        _ <- sendTransaction(signedTxHex)
        _ <- generateToAddress(1, 102, newAddress)
        _ <- checkStatus(startSessionResponse.sessionID)
          .flatMap(x =>
            generateToAddress(1, 1, newAddress) >> 
            mintToplBlock(1) >>
            IO.sleep(1.second) >> 
            IO.pure(x)
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
