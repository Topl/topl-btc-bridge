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
    import co.topl.bridge.implicits._

    assertIO(
      for {
        cwd <- process
          .ProcessBuilder("pwd")
          .spawn[IO]
          .use { getText }
        _ <- IO.println("cwd: " + cwd)
        initResult <- initUserWallet(1).use { getText }
        _ <- IO.println("initResult: " + initResult)
        addFellowshipResult <- addFellowship(1).use { getText }
        _ <- IO.println("addFellowshipResult: " + addFellowshipResult)
        addSecretResult <- addSecret(1).use { getText }
        _ <- IO.println("addSecretResult: " + addSecretResult)
        createWalletOut <- process
          .ProcessBuilder(DOCKER_CMD, createWallet: _*)
          .spawn[IO]
          .use { getText }
        _ <- IO.println("createWalletOut: " + createWalletOut)
        newAddress <- process // we get the new address
          .ProcessBuilder(DOCKER_CMD, getNewaddress: _*)
          .spawn[IO]
          .use(getText)
        _ <- IO.println("newAddress: " + newAddress)
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(1, 101, newAddress): _*)
          .spawn[IO]
          .use(_.exitValue)
        unspent <- process
          .ProcessBuilder(DOCKER_CMD, extractGetTxId: _*)
          .spawn[IO]
          .use(getText)
        // _ <- IO.println("unspent: " + unspent)
        txId <- IO.fromEither(
          parse(unspent).map(x => (x \\ "txid").head.asString.get)
        )
        _ <- IO.println("txId: " + txId)
        startSessionResponse <- EmberClientBuilder
          .default[IO]
          .build
          .use({ client =>
            client.expect[StartPeginSessionResponse](
              Request[IO](
                method = Method.POST,
                Uri
                  .fromString(
                    "http://127.0.0.1:4000/api/" + BridgeContants.START_PEGIN_SESSION_PATH
                  )
                  .toOption
                  .get
              ).withContentType(
                `Content-Type`.apply(MediaType.application.json)
              ).withEntity(
                StartPeginSessionRequest(
                  pkey =
                    "0295bb5a3b80eeccb1e38ab2cbac2545e9af6c7012cdc8d53bd276754c54fc2e4a",
                  sha256 = shaSecretMap(1)
                )
              )
            )
          })
        _ <- IO.println("script: " + startSessionResponse.script)
        _ <- IO.println("Escrow address: " + startSessionResponse.escrowAddress)
        addTemplateResult <- addTemplate(
          1,
          shaSecretMap(1),
          startSessionResponse.minHeight,
          startSessionResponse.maxHeight
        ).use { getText }
        _ <- IO.println("addTemplateResult: " + addTemplateResult)
        _ <- IO(Source.fromString(startSessionResponse.descriptor))
        bitcoinTx <- process
          .ProcessBuilder(
            DOCKER_CMD,
            createTx(
              txId,
              startSessionResponse.escrowAddress,
              BigDecimal("49.99")
            ): _*
          )
          .spawn[IO]
          .use(getText)
        _ <- IO.println("unspentTx: " + bitcoinTx)
        signedTx <- process
          .ProcessBuilder(DOCKER_CMD, signTransaction(bitcoinTx): _*)
          .spawn[IO]
          .use(getText)
        signedTxHex <- IO.fromEither(
          parse(signedTx).map(x => (x \\ "hex").head.asString.get)
        )
        sentTxId <- process
          .ProcessBuilder(DOCKER_CMD, sendTransaction(signedTxHex): _*)
          .spawn[IO]
          .use(getText)
        _ <- IO.println("Generating blocks..")
        _ <- IO.println("sentTxId: " + sentTxId)
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(1, 8, newAddress): _*)
          .spawn[IO]
          .use(_.exitValue)
        mintingStatusResponse <- EmberClientBuilder
          .default[IO]
          .build
          .use({ client =>
            (IO.println("Requesting..") >> client
              .expect[MintingStatusResponse](
                Request[IO](
                  method = Method.POST,
                  Uri
                    .fromString(
                      "http://127.0.0.1:4000/api/" + BridgeContants.TOPL_MINTING_STATUS
                    )
                    .toOption
                    .get
                ).withContentType(
                  `Content-Type`.apply(MediaType.application.json)
                ).withEntity(
                  MintingStatusRequest(startSessionResponse.sessionID)
                )
              )
              .flatMap(x =>
                IO.println(x.mintingStatus) >> IO.sleep(5.second) >> IO.pure(x)
              ))
              .iterateUntil(
                _.mintingStatus == "PeginSessionWaitingForRedemption"
              )
          })
        _ <- fs2.io
          .readInputStream[IO](
            IO(
              new ByteArrayInputStream(
                "".getBytes()
              )
            ),
            10
          )
          .through(Files[IO].writeAll(fs2.io.file.Path(vkFile)))
          .compile
          .drain
        importVkResult <- importVks(1).use { getError }
        _ <- IO.println("importVkResult: " + importVkResult)
        fundRedeemAddressTx <- fundRedeemAddressTx(1, 
          mintingStatusResponse.address
        ).use { getError }
        _ <- IO.println("fundRedeemAddressTx: " + fundRedeemAddressTx)
        proveFundRedeemAddressTxRes <- proveFundRedeemAddressTx(
          1,
          "fundRedeemTx.pbuf",
          "fundRedeemTxProved.pbuf"
        ).use { getText }
        _ <- IO.println(
          "proveFundRedeemAddressTxRes: " + proveFundRedeemAddressTxRes
        )
        broadcastFundRedeemAddressTxRes <- broadcastFundRedeemAddressTx(
          "fundRedeemTxProved.pbuf"
        ).use {
          getText
        }
        _ <- IO.println(
          "broadcastFundRedeemAddressTxRes: " + broadcastFundRedeemAddressTxRes
        )
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
          .use {
            getText
          }
        _ <- IO.println(
          "proveFundRedeemAddressTxRes: " + proveFundRedeemAddressTxRes
        )
        _ <- broadcastFundRedeemAddressTx("redeemTxProved.pbuf").use {
          getText
        }
        utxo <- getCurrentUtxosFromAddress(1, currentAddress)
          .use(
            getText
          )
          .iterateUntil(_.contains("Asset"))
        _ <- IO.println("utxos: " + utxo)
        _ <- IO.sleep(5.second)
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(1, 8, newAddress): _*)
          .spawn[IO]
          .use(_.exitValue)
        _ <- EmberClientBuilder
          .default[IO]
          .build
          .use({ client =>
            (IO.println("Requesting..") >> client
              .status(
                Request[IO](
                  method = Method.POST,
                  Uri
                    .fromString(
                      "http://127.0.0.1:4000/api/" + BridgeContants.TOPL_MINTING_STATUS
                    )
                    .toOption
                    .get
                ).withContentType(
                  `Content-Type`.apply(MediaType.application.json)
                ).withEntity(
                  MintingStatusRequest(startSessionResponse.sessionID)
                )
              ))
              .flatMap(x =>
                IO.println(x.code) >> process
                  .ProcessBuilder(
                    DOCKER_CMD,
                    generateToAddress(1, 1, newAddress): _*
                  )
                  .spawn[IO]
                  .use(_.exitValue) >> IO.sleep(5.second) >> IO.pure(x)
              )
              .iterateUntil(
                _.code == 404
              )
          })
        _ <- IO.println(
          s"Session ${startSessionResponse.sessionID} was successfully removed"
        )
      } yield (),
      ()
    )
  }

}
