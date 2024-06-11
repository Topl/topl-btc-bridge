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
import scala.io.Source

trait FailedRedemptionModule {

  // self BridgeIntegrationSpec
  self: BridgeIntegrationSpec =>

  def failedRedemption(): IO[Unit] = {
    import co.topl.bridge.implicits._

    assertIO(
      for {
        cwd <- process
          .ProcessBuilder("pwd")
          .spawn[IO]
          .use { getText }
        _ <- IO.println("cwd: " + cwd)
        initResult <- initUserWallet.use { getText }
        _ <- IO.println("initResult: " + initResult)
        addFellowshipResult <- addFellowship.use { getText }
        _ <- IO.println("addFellowshipResult: " + addFellowshipResult)
        addSecretResult <- addSecret.use { getText }
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
          .ProcessBuilder(DOCKER_CMD, generateToAddress(1, newAddress): _*)
          .spawn[IO]
          .use(_.exitValue)
        unspent <- process
          .ProcessBuilder(DOCKER_CMD, extractGetTxId: _*)
          .spawn[IO]
          .use(getText)
        _ <- IO.println("unspent: " + unspent)
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
        addTemplateResult <- addTemplate(
          sha256ToplSecret,
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
          .ProcessBuilder(DOCKER_CMD, generateToAddress(6, newAddress): _*)
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
                _.mintingStatus == "PeginSessionWaitingForRedemption"
              )
          })
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
                IO.println(x.code) >> IO.sleep(5.second) >> IO.pure(x)
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