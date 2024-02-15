package co.topl.bridge

import munit.CatsEffectSuite
import fs2.io.process
import cats.effect.IO
import org.http4s.ember.client._
import org.http4s.Method
import co.topl.shared.StartSessionRequest
import org.http4s.Request
import org.http4s.Uri
import org.http4s.EntityDecoder

class BridgeIntegrationSpec extends CatsEffectSuite {

  val DOCKER_CMD = "docker"

  val createWallet = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "-named",
    "createwallet",
    "wallet_name=testwallet"
  )
  val getNewaddress = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "-rpcwallet=testwallet",
    "getnewaddress"
  )
  def generateToAddress(address: String) = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "generatetoaddress",
    "101",
    address
  )

  val extractGetTxId = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "-rpcwallet=testwallet",
    "listunspent"
  )

  def getText(p: fs2.io.process.Process[IO]) =
    p.stdout
      .through(fs2.text.utf8Decode)
      .compile
      .foldMonoid
      .map(_.trim)
  def getError(p: fs2.io.process.Process[IO]) =
    p.stderr
      .through(fs2.text.utf8Decode)
      .compile
      .foldMonoid

  test("Bridge should mint assets on the Topl network") {
    import io.circe._, io.circe.parser._
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.HttpRoutes
    import org.http4s._
    import org.http4s.circe._
    import org.http4s.dsl.io._
    implicit val startSessionRequestDecoder
        : EntityEncoder[IO, StartSessionRequest] =
      jsonEncoderOf[IO, StartSessionRequest]
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
        _ <- IO.println("newAddress: " + newAddress)
        generatedBlock <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(newAddress): _*)
          .spawn[IO]
          .use(getText)
        _ <- IO.println("generatedBlock: " + generatedBlock)
        unspent <- process
          .ProcessBuilder(DOCKER_CMD, extractGetTxId: _*)
          .spawn[IO]
          .use(getText)
        _ <- IO.println("unspent: " + unspent)
        txId <- IO.fromEither(
          parse(unspent).map(x => (x \\ "txid").head.asString.get)
        )
        _ <- IO.println("txId: " + txId)
        jsonResponse <- EmberClientBuilder
          .default[IO]
          .build
          .use({ client =>
            client.expect[String](
              Request[IO](
                method = Method.POST,
                Uri
                  .fromString("http://localhost:3000/start-session")
                  .toOption
                  .get
              ).withEntity(
                StartSessionRequest(
                  pkey =
                    "0295bb5a3b80eeccb1e38ab2cbac2545e9af6c7012cdc8d53bd276754c54fc2e4a",
                  sha256 =
                    "497a39b618484855ebb5a2cabf6ee52ff092e7c17f8bfe79313529f9774f83a2"
                )
              )
            )
          })
        _ <- IO.println("jsonResponse: " + jsonResponse)
      } yield (),
      ()
    )
  }
}
