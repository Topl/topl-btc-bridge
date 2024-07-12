package co.topl.bridge
import cats.effect.IO

import scala.concurrent.duration._
import cats.effect.kernel.Ref

trait FailedMintingReorgModule {

  self: BridgeIntegrationSpec =>

  def failedMintingReorgModule(): IO[Unit] = {
    import org.typelevel.log4cats.syntax._
    import cats.implicits._

    assertIO(
      for {
        _ <- mintToplBlock(1, 1)
        bridgeNetworkAndName <- computeBridgeNetworkName
        _ <- pwd
        _ <- initToplWallet(2)
        _ <- addFellowship(2)
        _ <- addSecret(2)
        newAddress <- getNewAddress
        _ <- generateToAddress(1, 1, newAddress)
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession(2)
        _ <- addTemplate(
          2,
          shaSecretMap(2),
          startSessionResponse.minHeight,
          startSessionResponse.maxHeight
        )
        bitcoinTx <- createTx(
          txId,
          startSessionResponse.escrowAddress,
          btcAmount
        )
        signedTxHex <- signTransaction(bitcoinTx)
        // disconnect
        _ <- disconnectBridge(bridgeNetworkAndName._2, "bifrost02")
        _ <- info"Disconnected bridge"
        _ <- sendTransaction(signedTxHex)
        _ <- generateToAddress(1, 8, newAddress)
        // ref
        mutableRef <- Ref.of[IO, Int](0)
        _ <- (for {
          status <- checkMintingStatus(startSessionResponse.sessionID)
          nbTries <- mutableRef.updateAndGet(_ + 1)
          _ <-
            if (nbTries < 5)
              mintToplBlock(1, 1)
            else IO.unit
          _ <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(
            _.mintingStatus == "PeginSessionMintingTBTCConfirmation"
          )
        _ <- info"Session ${startSessionResponse.sessionID} went to PeginSessionMintingTBTCConfirmation"	
        _ <- List.fill(10)(mintToplBlock(2, 1)).sequence
        _ <- connectBridge(bridgeNetworkAndName._2, "bifrost02")
        _ <- (for {
          status <- checkMintingStatus(startSessionResponse.sessionID)
          _ <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(
            _.mintingStatus == "PeginSessionStateMintingTBTC"
          )
        _ <-
          info"Session ${startSessionResponse.sessionID} went back to PeginSessionWaitingForClaim again"
      } yield (),
      ()
    )
  }

}
