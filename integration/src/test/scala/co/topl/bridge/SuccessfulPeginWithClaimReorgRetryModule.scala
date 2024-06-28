package co.topl.bridge
import cats.effect.IO

import scala.concurrent.duration._

trait SuccessfulPeginWithClaimReorgRetryModule {

  self: BridgeIntegrationSpec =>

  def successfulPeginWithClaimErrorRetry(): IO[Unit] = {
    import org.typelevel.log4cats.syntax._

    assertIO(
      for {
        bridgeNetwork <- computeBridgeNetworkName
        ipBitcoin02 <- extractIpBtc(2, bridgeNetwork._1)
        ipBitcoin01 <- extractIpBtc(1, bridgeNetwork._1)
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
        _ <- sendTransaction(signedTxHex)
        _ <- generateToAddress(1, 8, newAddress)
        mintingStatusResponse <- (for {
          status <- checkMintingStatus(startSessionResponse.sessionID)
          _ <- mintToplBlock(1, 2)
          _ <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(
            _.mintingStatus == "PeginSessionWaitingForRedemption"
          )
        _ <- createVkFile(vkFile)
        _ <- importVks(2)
        _ <- fundRedeemAddressTx(
          2,
          mintingStatusResponse.address
        ).use { getText }
        _ <- IO.println("fundRedeemAddressTx: " + fundRedeemAddressTx)
        proveFundRedeemAddressTxRes <- proveFundRedeemAddressTx(
          2,
          "fundRedeemTx.pbuf",
          "fundRedeemTxProved.pbuf"
        ).use { getText }
        _ <- IO.println(
          "proveFundRedeemAddressTxRes: " + proveFundRedeemAddressTxRes
        )
        _ <- broadcastFundRedeemAddressTx("fundRedeemTxProved.pbuf")
        _ <- mintToplBlock(1, 1)
        utxo <- getCurrentUtxosFromAddress(2, mintingStatusResponse.address)
          .iterateUntil(_.contains("LVL"))
        groupId = extractGroupId(utxo)
        seriesId = extractSeriesId(utxo)
        currentAddress <- currentAddress(2)
        _ <- redeemAddressTx(
          2,
          currentAddress,
          BigDecimal((btcAmount.toInt.get - 1).toString + "99000000").toLong,
          groupId,
          seriesId
        ).use { getText }
        _ <- IO.println("redeemAddressTx: " + redeemAddressTx)
        proveFundRedeemAddressTxRes <- proveFundRedeemAddressTx(
          2,
          "redeemTx.pbuf",
          "redeemTxProved.pbuf"
        )
          .use {
            getText
          }
        _ <- IO.println(
          "proveFundRedeemAddressTxRes: " + proveFundRedeemAddressTxRes
        )
        // disconnect networks
        _ <- setNetworkActive(2, false)
        _ <- setNetworkActive(1, false)
        // broadcast
        _ <- broadcastFundRedeemAddressTx("redeemTxProved.pbuf")
        _ <- mintToplBlock(1, 8)
        _ <- getCurrentUtxosFromAddress(2, currentAddress)
          .iterateUntil(_.contains("Asset"))
        _ <- (for {
          status <- checkMintingStatus(startSessionResponse.sessionID)
          _ <- generateToAddress(1, 1, newAddress)
          _ <- IO.sleep(1.second)
        } yield status)
          .iterateUntil(
            _.mintingStatus == "PeginSessionWaitingForClaimBTCConfirmation"
          )
        _ <- generateToAddress(2, 8, newAddress)
        // reconnect networks
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
            _.mintingStatus == "PeginSessionWaitingForClaim"
          )
          _ <- (for {
            x <- checkStatus(startSessionResponse.sessionID)
            _ <- generateToAddress(1, 2, newAddress)
          _ <- IO.sleep(5.second)
        } yield x)
          .iterateUntil(
            _.code == 404
          )
        _ <-
          info"Session ${startSessionResponse.sessionID} went back to PeginSessionWaitingForClaim again"
      } yield (),
      ()
    )
  }

}
