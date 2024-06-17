package co.topl.bridge

import cats.effect.IO
import co.topl.shared.BridgeContants
import co.topl.shared.MintingStatusRequest
import co.topl.shared.MintingStatusResponse
import co.topl.shared.StartPeginSessionRequest
import co.topl.shared.StartPeginSessionResponse
import fs2.io.process
import io.circe.parser._
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s._
import org.http4s.ember.client._
import org.http4s.headers.`Content-Type`

import scala.concurrent.duration._

trait FailedPeginNoDepositWithReorgModule {

  self: BridgeIntegrationSpec =>

  def failedPeginNoDepositWithReorg(): IO[Unit] = {
    import co.topl.bridge.implicits._

    assertIO(
      for {
        cwd <- process
          .ProcessBuilder("pwd")
          .spawn[IO]
          .use { getText }
        _ <- IO.println("cwd: " + cwd)
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
                  sha256 = sha256ToplSecret
                )
              )
            )
          })
        _ <- IO.println("Escrow address: " + startSessionResponse.escrowAddress)
        bridgeNetwork <- computeBridgeNetworkName
        // parse
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
        _ <- process
          .ProcessBuilder(DOCKER_CMD, setNetworkActive(2, false): _*)
          .spawn[IO]
          .use { getText }
        _ <- process
          .ProcessBuilder(DOCKER_CMD, setNetworkActive(1, false): _*)
          .spawn[IO]
          .use { getText }
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
        _ <- IO.println("Generating blocks..")
        sentTxId <- process
          .ProcessBuilder(DOCKER_CMD, sendTransaction(signedTxHex): _*)
          .spawn[IO]
          .use(getText)
        _ <- IO.println("sentTxId: " + sentTxId)
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(1, 2, newAddress): _*)
          .spawn[IO]
          .use(_.exitValue)
        _ <- EmberClientBuilder
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
                _.mintingStatus == "PeginSessionWaitingForEscrowBTCConfirmation"
              )
          })
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(1, 2, newAddress): _*)
          .spawn[IO]
          .use(_.exitValue)
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(2, 8, newAddress): _*)
          .spawn[IO]
          .use(_.exitValue)
        // add node
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
              ))
              .flatMap(x =>
                IO.println(x.mintingStatus) >> IO.sleep(5.second) >> IO.pure(x)
              )
              .iterateUntil(
                _.mintingStatus == "PeginSessionStateWaitingForBTC"
              )
          })
        _ <- IO.println(
          s"Session ${startSessionResponse.sessionID} went back to wait again"
        )
      } yield (),
      ()
    )
  }
}
