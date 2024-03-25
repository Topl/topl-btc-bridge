package co.topl.bridge

import cats.effect.IO
import co.topl.shared.BridgeContants
import co.topl.shared.ConfirmDepositRequest
import co.topl.shared.ConfirmDepositResponse
import co.topl.shared.ConfirmRedemptionRequest
import co.topl.shared.ConfirmRedemptionResponse
import co.topl.shared.StartPeginSessionRequest
import co.topl.shared.StartPeginSessionResponse
import co.topl.shared.SyncWalletRequest
import fs2.io.process
import munit.CatsEffectSuite
import org.checkerframework.checker.units.qual.g
import org.http4s.EntityDecoder
import org.http4s.Method
import org.http4s.Request
import org.http4s.Uri
import org.http4s.ember.client._
import org.http4s.headers.`Content-Type`

import scala.concurrent.duration._

class BridgeIntegrationSpec extends CatsEffectSuite {

  val DOCKER_CMD = "docker"

  override val munitTimeout = Duration(180, "s")

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
  def generateToAddress(blocks: Int, address: String) = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "generatetoaddress",
    blocks.toString,
    address
  )
  def createTransaction(address: String) = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "generatetoaddress",
    "101",
    address
  )
  def signTransaction(tx: String) = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "-rpcwallet=testwallet",
    "signrawtransactionwithwallet",
    tx
  )
  def sendTransaction(signedTx: String) = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "sendrawtransaction",
    signedTx
  )

  val extractGetTxId = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "-rpcwallet=testwallet",
    "listunspent"
  )

  def createTx(txId: String, address: String) = Seq(
    "exec",
    "bitcoin",
    "bitcoin-tx",
    "-regtest",
    "-create",
    s"in=$txId:0",
    s"outaddr=49.99:$address"
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

  val getCurrentUtxos = process
    .ProcessBuilder(
      "./cs",
      Seq(
        "launch",
        "-r",
        "https://s01.oss.sonatype.org/content/repositories/releases",
        "co.topl:brambl-cli_2.13:2.0.0-beta1",
        "--",
        "genus-query",
        "utxo-by-address",
        "--host",
        "localhost",
        "--port",
        "9084",
        "--secure",
        "false",
        "--walletdb",
        "data/topl-wallet.db"
      ): _*
    )
    .spawn[IO]

  test("Bridge should mint assets on the Topl network") {
    import io.circe._, io.circe.parser._
    import io.circe.generic.auto._
    import io.circe.syntax._
    import org.http4s.HttpRoutes
    import org.http4s._
    import org.http4s.circe._
    import org.http4s.dsl.io._
    implicit val startSessionRequestDecoder
        : EntityEncoder[IO, StartPeginSessionRequest] =
      jsonEncoderOf[IO, StartPeginSessionRequest]
    implicit val syncWalletRequestDecoder
        : EntityEncoder[IO, SyncWalletRequest] =
      jsonEncoderOf[IO, SyncWalletRequest]
    implicit val startSessionResponse: EntityDecoder[IO, StartPeginSessionResponse] =
      jsonOf[IO, StartPeginSessionResponse]
    implicit val confirmRedemptionRequestDecoder
        : EntityEncoder[IO, ConfirmRedemptionRequest] =
      jsonEncoderOf[IO, ConfirmRedemptionRequest]
    implicit val confirmRedemptionResponse
        : EntityDecoder[IO, ConfirmRedemptionResponse] =
      jsonOf[IO, ConfirmRedemptionResponse]
    implicit val confirmDepositRequestEncoder
        : EntityEncoder[IO, ConfirmDepositRequest] =
      jsonEncoderOf[IO, ConfirmDepositRequest]
    implicit val confirmDepositRequestDecoder
        : EntityDecoder[IO, ConfirmDepositResponse] =
      jsonOf[IO, ConfirmDepositResponse]
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
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(101, newAddress): _*)
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
                    "http://127.0.0.1:3000/api/" + BridgeContants.START_PEGIN_SESSION_PATH
                  )
                  .toOption
                  .get
              ).withContentType(
                `Content-Type`.apply(MediaType.application.json)
              ).withEntity(
                StartPeginSessionRequest(
                  pkey =
                    "0295bb5a3b80eeccb1e38ab2cbac2545e9af6c7012cdc8d53bd276754c54fc2e4a",
                  sha256 =
                    "497a39b618484855ebb5a2cabf6ee52ff092e7c17f8bfe79313529f9774f83a2"
                )
              )
            )
          })
        syncWalletResponse <- EmberClientBuilder
          .default[IO]
          .build
          .use({ client =>
            client.expect[String](
              Request[IO](
                method = Method.POST,
                Uri
                  .fromString("http://127.0.0.1:3000/api/sync-wallet")
                  .toOption
                  .get
              ).withContentType(
                `Content-Type`.apply(MediaType.application.json)
              ).withEntity(
                SyncWalletRequest(
                  "secret"
                )
              )
            )
          })
        _ <- IO.println("syncWalletResponse: " + syncWalletResponse)
        bitcoinTx <- process
          .ProcessBuilder(
            DOCKER_CMD,
            createTx(txId, startSessionResponse.escrowAddress): _*
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
        genusQueryresult <- getCurrentUtxos.use(getText)
        txId = genusQueryresult
          .split("\n")
          .filter(_.endsWith("#1"))
          .head
          .split(":")
          .map(_.trim)
          .tail
          .head
          .split("#")
          .head
        confirmDepositResponse <- EmberClientBuilder
          .default[IO]
          .build
          .use({ client =>
            client.expect[ConfirmDepositResponse](
              Request[IO](
                method = Method.POST,
                Uri
                  .fromString("http://127.0.0.1:3000/api/confirm-deposit-btc")
                  .toOption
                  .get
              ).withContentType(
                `Content-Type`.apply(MediaType.application.json)
              ).withEntity(
                ConfirmDepositRequest(
                  startSessionResponse.sessionID,
                  4999000000L
                )
              )
            )
          })
        _ <- IO.println("confirmDepositResponse: " + confirmDepositResponse)
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(10, newAddress): _*)
          .spawn[IO]
          .use(_.exitValue)
        confirmRedemptionResponse <- EmberClientBuilder
          .default[IO]
          .build
          .use({ client =>
            client.expect[ConfirmRedemptionResponse](
              Request[IO](
                method = Method.POST,
                Uri
                  .fromString("http://127.0.0.1:3000/confirm-redemption")
                  .toOption
                  .get
              ).withContentType(
                `Content-Type`.apply(MediaType.application.json)
              ).withEntity(
                ConfirmRedemptionRequest(
                  startSessionResponse.sessionID,
                  sentTxId,
                  0,
                  2,
                  4999000000L,
                  "secret"
                )
              )
            )
          })
        _ <- IO.println("startSessionResponse: " + confirmRedemptionResponse)
        _ <- IO.sleep(10.seconds)
        _ <- getCurrentUtxos.use(_.exitValue).iterateUntil(_ == 0)
        _ <- assertIOBoolean(
          getCurrentUtxos.use(getText).map(_.contains("Asset"))
        )
      } yield (),
      ()
    )
  }
}
