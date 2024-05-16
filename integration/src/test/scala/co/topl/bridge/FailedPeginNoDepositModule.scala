package co.topl.bridge

import cats.effect.IO
import co.topl.shared.BridgeContants
import co.topl.shared.MintingStatusRequest
import co.topl.shared.StartPeginSessionRequest
import co.topl.shared.StartPeginSessionResponse
import fs2.io.process
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s._
import org.http4s.ember.client._
import org.http4s.headers.`Content-Type`

import scala.concurrent.duration._

trait FailedPeginNoDepositModule {

  self: BridgeIntegrationSpec =>

  def failedPeginNoDeposit(): IO[Unit] = {
    import co.topl.bridge.implicits._

    assertIO(
      for {
        createWalletOut <- process
          .ProcessBuilder(DOCKER_CMD, createWallet: _*)
          .spawn[IO]
          .use { getText }
        _ <- IO.println("createWalletOut: " + createWalletOut)
        newAddress <- process // we get the new address
          .ProcessBuilder(DOCKER_CMD, getNewaddress: _*)
          .spawn[IO]
          .use(getText)
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
        _ <- IO.println("Generating blocks..")
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(102, newAddress): _*)
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
