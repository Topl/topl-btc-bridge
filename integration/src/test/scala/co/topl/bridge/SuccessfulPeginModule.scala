package co.topl.bridge
import cats.effect.IO
import co.topl.shared.BridgeContants
import co.topl.shared.MintingStatusRequest
import co.topl.shared.MintingStatusResponse
import co.topl.shared.StartPeginSessionRequest
import co.topl.shared.StartPeginSessionResponse
import fs2.io.file.Files
import fs2.io.process
import io.circe.parser._
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s._
import org.http4s.ember.client._
import org.http4s.headers.`Content-Type`

import java.io.ByteArrayInputStream
import scala.concurrent.duration._
import scala.io.Source

trait SuccessfulPeginModule {

  // self BridgeIntegrationSpec
  self: BridgeIntegrationSpec =>

  def successfulPegin(): IO[Unit] = {

    assertIO(
      for {
        _ <- pwd
        _ <- mintToplBlock(1) // this will update the current topl height on the node, node should not work without this
        _ <- initToplWallet(1)
        _ <- addFellowship(1)
        _ <- addSecret(1)
        newAddress <- getNewAddress
        _ <- generateToAddress(1, 101, newAddress)
        txIdAndBTCAmount <- extractGetTxIdAndAmount
        (txId, btcAmount, btcAmountLong) = txIdAndBTCAmount
        startSessionResponse <- startSession
        _ <- addTemplate(
          1,
          shaSecretMap(1),
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
        mintingStatusResponse <-
          (for {
            status <- checkMintingStatus(startSessionResponse.sessionID)
            _ <- mintToplBlock(2)
            _ <- IO.sleep(1.second)
          } yield status)
            .iterateUntil(_.mintingStatus == "PeginSessionWaitingForRedemption")
        _ <- createVkFile(vkFile)
        _ <- importVks(1)
        _ <- fundRedeemAddressTx(1, mintingStatusResponse.address)
        _ <- proveFundRedeemAddressTx(
          1,
          "fundRedeemTx.pbuf",
          "fundRedeemTxProved.pbuf"
        ).use { getText }
        _ <- IO.println(
          "proveFundRedeemAddressTxRes: " + proveFundRedeemAddressTxRes
        )
        _ <- broadcastFundRedeemAddressTx("fundRedeemTxProved.pbuf")
        _ <- mintToplBlock(1)
        utxo <- getCurrentUtxosFromAddress(1, mintingStatusResponse.address)
          .use(
            getText
          )
          .iterateUntil(_.contains("LVL"))
        _ <- IO.println("utxos: " + utxo)
        groupId = utxo
          .split("\n")
          .filter(_.contains("GroupId"))
          .head
          .split(":")
          .last
          .trim()
        seriesId = utxo
          .split("\n")
          .filter(_.contains("SeriesId"))
          .head
          .split(":")
          .last
          .trim()
        _ <- IO.println("groupId: " + groupId)
        _ <- IO.println("seriesId: " + seriesId)
        currentAddress <- currentAddress(1).use { getText }
        redeemAddressTx <- redeemAddressTx(
          1,
          currentAddress,
          4999000000L,
          groupId,
          seriesId
        ).use { getText }
        _ <- IO.println("redeemAddressTx: " + redeemAddressTx)
        proveFundRedeemAddressTxRes <- proveFundRedeemAddressTx(
          1,
          "redeemTx.pbuf",
          "redeemTxProved.pbuf"
        )
        _ <- broadcastFundRedeemAddressTx("redeemTxProved.pbuf")
        _ <- mintToplBlock(8)
        _ <- getCurrentUtxosFromAddress(1, currentAddress)
          .iterateUntil(_.contains("Asset"))
        _ <- generateToAddress(1, 3, newAddress)
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
