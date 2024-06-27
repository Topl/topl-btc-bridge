package co.topl.bridge

import cats.effect.IO
import org.typelevel.log4cats.syntax._

import scala.concurrent.duration._

trait FailedPeginNoDepositWithReorgModule {

  self: BridgeIntegrationSpec =>

  def failedPeginNoDepositWithReorg(): IO[Unit] = {

    assertIO(
      for {
        newAddress <- getNewAddress
        _ <- generateToAddress(1, 1, newAddress)
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(1)
        bridgeNetwork <- computeBridgeNetworkName
        // parse
        ipBitcoin02 <- extractIp(2, bridgeNetwork)
        // parse
        ipBitcoin01 <- extractIp(1, bridgeNetwork)
        _ <- setNetworkActive(2, false)
        _ <- setNetworkActive(1, false)
        bitcoinTx <- createTx(
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx)
        _ <- sendTransaction(signedTxHex)
        _ <- generateToAddress(1, 2, newAddress)
        _ <- (for {
          status <- checkMintingStatus(startSessionResponse.sessionID)
          _ <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(
            _.mintingStatus == "PeginSessionWaitingForEscrowBTCConfirmation"
          )
        _ <- generateToAddress(1, 2, newAddress)
        _ <- generateToAddress(2, 8, newAddress)
        // reconnect network
        _ <- setNetworkActive(2, true)
        _ <- setNetworkActive(1, true)
        // force connection
        _ <- forceConnection(1, ipBitcoin02, 18444)
        _ <- forceConnection(2, ipBitcoin01, 18444)
        _ <- (for {
          status <- checkMintingStatus(startSessionResponse.sessionID)
          _ <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(
            _.mintingStatus == "PeginSessionStateWaitingForBTC"
          )
        _ <- generateToAddress(1, 8, newAddress)
        _ <- info"Session ${startSessionResponse.sessionID} went back to wait again"
      } yield (),
      ()
    )
  }
}
