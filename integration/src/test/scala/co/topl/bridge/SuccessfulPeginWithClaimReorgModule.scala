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

trait SuccessfulPeginWithClaimReorgModule {

  // self BridgeIntegrationSpec
  self: BridgeIntegrationSpec =>

  def successfulPeginWithClaimError(): IO[Unit] = {
    import co.topl.bridge.implicits._
    import org.typelevel.log4cats.syntax._
    val logger =
      org.typelevel.log4cats.slf4j.Slf4jLogger
        .getLoggerFromName[IO]("successfulPeginWithClaimError")

    assertIO(
      for {
        bridgeNetwork <- computeBridgeNetworkName
        ipBitcoin02 <- IO.fromEither(
          parse(bridgeNetwork)
            .map(x =>
              (((x.asArray.get.head \\ "Containers").head.asObject.map { x =>
                x.filter(x => (x._2 \\ "Name").head.asString.get == "bitcoin02")
                  .values
                  .head
              }).get \\ "IPv4Address").head.asString.get
                .split("/")
                .head
            )
        )
        // print IP BTC 02
        _ <- IO.println("ipBitcoin02: " + ipBitcoin02)
        // parse
        ipBitcoin01 <- IO.fromEither(
          parse(bridgeNetwork)
            .map(x =>
              (((x.asArray.get.head \\ "Containers").head.asObject.map { x =>
                x.filter(x => (x._2 \\ "Name").head.asString.get == "bitcoin01")
                  .values
                  .head
              }).get \\ "IPv4Address").head.asString.get
                .split("/")
                .head
            )
        )
        // print IP BTC 01
        _ <- IO.println("ipBitcoin01: " + ipBitcoin01)
        cwd <- process
          .ProcessBuilder("pwd")
          .spawn[IO]
          .use { getText }
        _ <- IO.println("cwd: " + cwd)
        initResult <- initUserWallet(2).use { getText }
        _ <- IO.println("initResult: " + initResult)
        addFellowshipResult <- addFellowship(2).use { getText }
        _ <- IO.println("addFellowshipResult: " + addFellowshipResult)
        addSecretResult <- addSecret(2).use { getText }
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
          .ProcessBuilder(DOCKER_CMD, generateToAddress(1, 1, newAddress): _*)
          .spawn[IO]
          .use(_.exitValue)
        unspent <- process
          .ProcessBuilder(DOCKER_CMD, extractGetTxId: _*)
          .spawn[IO]
          .use(getText)
        txId <- IO.fromEither(
          parse(unspent).map(x => (x \\ "txid").head.asString.get)
        )
        btcAmount <- IO.fromEither(
          parse(unspent).map(x => (x \\ "amount").head.asNumber.get)
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
                  sha256 = shaSecretMap(2)
                )
              )
            )
          })
        _ <- IO.println("script: " + startSessionResponse.script)
        _ <- IO.println("Escrow address: " + startSessionResponse.escrowAddress)
        addTemplateResult <- addTemplate(
          2,
          shaSecretMap(2),
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
              btcAmount.toBigDecimal.get - BigDecimal("0.01")
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
          .use(getError)
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
            (IO.println(
              "Requesting 1.. " + "sessionId: " + startSessionResponse.sessionID
            ) >> client
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
        importVkResult <- importVks(2).use { getText }
        _ <- IO.println("importVkResult: " + importVkResult)
        fundRedeemAddressTx <- fundRedeemAddressTx(
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
        broadcastFundRedeemAddressTxRes <- broadcastFundRedeemAddressTx(
          "fundRedeemTxProved.pbuf"
        ).use {
          getText
        }
        _ <- IO.println(
          "broadcastFundRedeemAddressTxRes: " + broadcastFundRedeemAddressTxRes
        )
        utxo <- (getCurrentUtxosFromAddress(2, mintingStatusResponse.address)
          .use(
            getText
          )
          .flatMap(x =>
            info"waiting for 2 seconds" (logger) >> IO.sleep(2.second) >> IO
              .pure(x)
          ))
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
        currentAddress <- currentAddress(2).use { getText }
        _ <- IO.println("btcAmount.toString: " + btcAmount.toString)
        redeemAddressTx <- redeemAddressTx(
          2,
          currentAddress,
          BigDecimal(btcAmount.toString + "99000000").toLong,
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
        _ <- process
          .ProcessBuilder(DOCKER_CMD, setNetworkActive(2, false): _*)
          .spawn[IO]
          .use { getText }
        _ <- process
          .ProcessBuilder(DOCKER_CMD, setNetworkActive(1, false): _*)
          .spawn[IO]
          .use { getText }
        // broadcast
        redeemTxProved <- broadcastFundRedeemAddressTx("redeemTxProved.pbuf")
          .use {
            getError
          }
        _ <- IO.println("redeemTxProved: " + redeemTxProved)
        utxo <- getCurrentUtxosFromAddress(2, currentAddress)
          .use(
            getText
          )
          .iterateUntil(_.contains("Asset"))
        _ <- IO.println("utxos: " + utxo)
        _ <- EmberClientBuilder
          .default[IO]
          .build
          .use({ client =>
            ((IO.println("Requesting 2..") >> client
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
              ))
              .flatMap(x =>
                IO.println(x.mintingStatus) >> process
                  .ProcessBuilder(
                    DOCKER_CMD,
                    generateToAddress(1, 1, newAddress): _*
                  )
                  .spawn[IO]
                  .use(_.exitValue) >>
                  IO.sleep(5.second) >> IO.pure(x)
              ))
              .iterateUntil(
                _.mintingStatus == "PeginSessionWaitingForClaimBTCConfirmation"
              )
          })
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(2, 8, newAddress): _*)
          .spawn[IO]
          .use(_.exitValue)
        // reconnect networks
        _ <- process
          .ProcessBuilder(DOCKER_CMD, setNetworkActive(2, true): _*)
          .spawn[IO]
          .use { getText }
        _ <- process
          .ProcessBuilder(DOCKER_CMD, setNetworkActive(1, true): _*)
          .spawn[IO]
          .use { getText }
        // force connection
        _ <- process
          .ProcessBuilder(
            DOCKER_CMD,
            forceConnection(1, ipBitcoin02, 18444): _*
          )
          .spawn[IO]
          .use { getText }
        _ <- process
          .ProcessBuilder(
            DOCKER_CMD,
            forceConnection(2, ipBitcoin01, 18444): _*
          )
          .spawn[IO]
          .use { getText }
        _ <- EmberClientBuilder
          .default[IO]
          .build
          .use({ client =>
            (IO.println("Requesting 3..") >> client
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
              ))
              .flatMap(x =>
                IO.println(x.mintingStatus) >> IO.sleep(5.second) >> IO.pure(x)
              )
              .iterateUntil(
                _.mintingStatus == "PeginSessionWaitingForClaim"
              )
          })
        _ <- IO.println(
          s"Session ${startSessionResponse.sessionID} went back to PeginSessionWaitingForClaim again"
        )
      } yield (),
      ()
    )
  }

}
