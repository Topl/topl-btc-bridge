package co.topl.bridge

import cats.effect.IO
import co.topl.shared.BridgeContants
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
import org.checkerframework.checker.units.qual.s
import co.topl.shared.MintingStatusRequest
import co.topl.shared.MintingStatusResponse
import scala.io.Source
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.Paths
import java.io.InputStream
import java.io.ByteArrayInputStream

class BridgeIntegrationSpec extends CatsEffectSuite {

  val DOCKER_CMD = "docker"

  override val munitTimeout = Duration(180, "s")

  override val munitIOTimeout = Duration(180, "s")

  val createWallet = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "-named",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "createwallet",
    "wallet_name=testwallet"
  )
  val getNewaddress = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "-regtest",
    "-rpcwallet=testwallet",
    "getnewaddress"
  )
  def generateToAddress(blocks: Int, address: String) = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "generatetoaddress",
    blocks.toString,
    address
  )

  def createTransaction(address: String) = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "generatetoaddress",
    "101",
    address
  )
  def signTransaction(tx: String) = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "-rpcwallet=testwallet",
    "signrawtransactionwithwallet",
    tx
  )
  def sendTransaction(signedTx: String) = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "sendrawtransaction",
    signedTx
  )

  val extractGetTxId = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
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

  // brambl-cli fellowships add --walletdb user-wallet.db --fellowship-name bridge
  val addFellowship = process
    .ProcessBuilder(
      "cs",
      Seq(
        "launch",
        "-r",
        "https://s01.oss.sonatype.org/content/repositories/releases",
        "co.topl:brambl-cli_2.13:2.0.0-beta5",
        "--",
        "fellowships",
        "add",
        "--walletdb",
        "user-wallet.db",
        "--fellowship-name",
        "bridge"
      ): _*
    )
    .spawn[IO]

  val cleanupDir = FunFixture[Unit](
    setup = { _ =>
      try {
        Files.delete(Paths.get(userWalletDb))
        Files.delete(Paths.get(userWalletMnemonic))
        Files.delete(Paths.get(userWalletJson))
        Files.delete(Paths.get(vkFile))
        Files.delete(Paths.get("fundRedeemTx.pbuf"))
        Files.delete(Paths.get("fundRedeemTxProved.pbuf"))
        Files.delete(Paths.get("redeemTx.pbuf"))
        Files.delete(Paths.get("redeemTxProved.pbuf"))
      } catch {
        case _: Throwable => ()
      }
    },
    teardown = { _ =>
      ()
    }
  )

  cleanupDir.test("Bridge should mint assets on the Topl network") { _ =>
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
    implicit val mintingStatusRequesEncoder
        : EntityEncoder[IO, MintingStatusRequest] =
      jsonEncoderOf[IO, MintingStatusRequest]
    implicit val startSessionResponse
        : EntityDecoder[IO, StartPeginSessionResponse] =
      jsonOf[IO, StartPeginSessionResponse]
    implicit val MintingStatusResponseDecoder
        : EntityDecoder[IO, MintingStatusResponse] =
      jsonOf[IO, MintingStatusResponse]
    assertIO(
      for {
        initResult <- initUserWallet.use { getText }
        _ <- IO.println("initResult: " + initResult)
        addFellowshipResult <- addFellowship.use { getText }
        _ <- IO.println("addFellowshipResult: " + addFellowshipResult)
        addTemplateResult <- addTemplate(sha256ToplSecret).use { getText }
        _ <- IO.println("addTemplateResult: " + addTemplateResult)
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
        _ <- IO(Source.fromString(startSessionResponse.descriptor))
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
        _ <- IO.println("Generating blocks..")
        _ <- IO.println("sentTxId: " + sentTxId)
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(6, newAddress): _*)
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
                mintingStatusResponse.bridgePKey.getBytes()
              )
            ),
            10
          )
          .through(fs2.io.file.writeAll(Paths.get(vkFile)))
          .compile
          .drain
        importVkResult <- importVks.use { getText }
        _ <- IO.println("importVkResult: " + importVkResult)
        fundRedeemAddressTx <- fundRedeemAddressTx(
          mintingStatusResponse.address
        ).use { getText }
        _ <- IO.println("fundRedeemAddressTx: " + fundRedeemAddressTx)
        proveFundRedeemAddressTxRes <- proveFundRedeemAddressTx(
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
        utxo <- getCurrentUtxosFromAddress(mintingStatusResponse.address)
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
        currentAddress <- currentAddress.use { getText }
        redeemAddressTx <- redeemAddressTx(
          currentAddress,
          4999000000L,
          groupId,
          seriesId
        ).use { getText }
        _ <- IO.println("redeemAddressTx: " + redeemAddressTx)
        proveFundRedeemAddressTxRes <- proveFundRedeemAddressTx(
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
        utxo <- getCurrentUtxosFromAddress(currentAddress)
          .use(
            getText
          )
          .iterateUntil(_.contains("Asset"))
        _ <- IO.println("utxos: " + utxo)
      } yield (),
      ()
    )
  }
}
