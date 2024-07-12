package co.topl.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

trait FailedPeginNoMintModule {

  self: BridgeIntegrationSpec =>

  def failedPeginNoMint(): IO[Unit] = {

    assertIO(
      for {
        // ref <- Ref.of[IO, Int](0)
        _ <- mintToplBlock(1, 1)
        _ <- IO.sleep(1.second)
        newAddress <- getNewAddress
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(1)
        bitcoinTx <- createTx(
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx)
        _ <- sendTransaction(signedTxHex)
        _ <- generateToAddress(1, 52, newAddress)
        _ <- checkStatus(startSessionResponse.sessionID)
          .flatMap(x =>
            for {
              _ <- generateToAddress(1, 5, newAddress)
              _ <- IO.sleep(1.second)
            } yield x
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
