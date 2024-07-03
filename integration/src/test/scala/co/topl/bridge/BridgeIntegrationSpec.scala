package co.topl.bridge

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.kernel.Fiber
import fs2.io.process
import io.circe.parser._
import munit.AnyFixture
import munit.CatsEffectSuite
import munit.FutureFixture

import java.nio.file.Files
import java.nio.file.Paths
import scala.concurrent.duration._

class BridgeIntegrationSpec
    extends CatsEffectSuite
    with SuccessfulPeginModule
    with FailedPeginNoDepositModule
    with FailedPeginNoMintModule
    with FailedRedemptionModule
    with FailedPeginNoDepositWithReorgModule
    with SuccessfulPeginWithClaimReorgModule
    with SuccessfulPeginWithClaimReorgRetryModule
    with FailedMintingReorgModule {

  val DOCKER_CMD = "docker"

  override val munitIOTimeout = Duration(180, "s")

  implicit val logger: Logger[IO] =
    org.typelevel.log4cats.slf4j.Slf4jLogger
      .getLoggerFromName[IO]("it-test")

  import org.typelevel.log4cats.syntax._

  val computeBridgeNetworkName = for {
    // network ls
    networkLs <- process
      .ProcessBuilder(DOCKER_CMD, networkLs: _*)
      .spawn[IO]
      .use { getText }
    // extract the string that starts with github_network_
    // the format is
    // NETWORK ID     NAME      DRIVER    SCOPE
    // 7b1e3b1b1b1b   github_network_bitcoin01   bridge   local
    pattern = ".*?(github_network_\\S+)\\s+.*".r
    networkName = pattern.findFirstMatchIn(networkLs) match {
      case Some(m) =>
        m.group(1) // Extract the first group matching the pattern
      case None => "bridge"
    }
    // inspect bridge
    bridgeNetwork <- process
      .ProcessBuilder(DOCKER_CMD, inspectBridge(networkName): _*)
      .spawn[IO]
      .use { getText }
    // print bridgeNetwork
    _ <- info"bridgeNetwork: $bridgeNetwork"
  } yield (bridgeNetwork, networkName)

  lazy val toplWalletDb =
    Option(System.getenv("TOPL_WALLET_DB")).getOrElse("topl-wallet.db")
  lazy val toplWalletJson =
    Option(System.getenv("TOPL_WALLET_JSON")).getOrElse("topl-wallet.json")

  val startServer: AnyFixture[Unit] =
    new FutureFixture[Unit]("server setup") {

      var fiber: Fiber[IO, Throwable, ExitCode] = _
      def apply() = fiber: Unit

      override def beforeAll() = {
        (for {
          _ <- pwd
          currentAddress <- process
            .ProcessBuilder(
              CS_CMD,
              csParams ++ Seq(
                "wallet",
                "current-address",
                "--walletdb",
                toplWalletDb
              ): _*
            )
            .spawn[IO]
            .use { getText }
          utxo <- process
            .ProcessBuilder(
              CS_CMD,
              csParams ++ Seq(
                "genus-query",
                "utxo-by-address",
                "--host",
                "localhost",
                "--port",
                "9084",
                "--secure",
                "false",
                "--walletdb",
                toplWalletDb,
                "--from-address",
                currentAddress
              ): _*
            )
            .spawn[IO]
            .use(
              getText
            )
          _ <- IO.println("utxo: " + utxo)
          (groupId, seriesId) = extractIds(utxo)
          _ <- IO.asyncForIO
            .both(
              IO.asyncForIO
                .start(
                  consensus.Main.run(
                    List(
                      "--btc-wallet-seed-file",
                      "src/test/resources/wallet.json",
                      "--btc-peg-in-seed-file",
                      "src/test/resources/pegin-wallet.json",
                      "--topl-wallet-seed-file",
                      toplWalletJson,
                      "--topl-wallet-db",
                      toplWalletDb,
                      "--btc-url",
                      "http://localhost",
                      "--topl-blocks-to-recover",
                      "15",
                      "--abtc-group-id",
                      groupId,
                      "--abtc-series-id",
                      seriesId
                    )
                  )
                ),
              IO.sleep(10.seconds)
            )
            .map { case (f, _) =>
              fiber = f
            }
            .void
          bridgeNetwork <- computeBridgeNetworkName
          _ <- IO.println("bridgeNetwork: " + bridgeNetwork)
          // parse
          ipBitcoin02 <- IO.fromEither(
            parse(bridgeNetwork._1)
              .map(x =>
                (((x.asArray.get.head \\ "Containers").head.asObject.map { x =>
                  x.filter(x =>
                    (x._2 \\ "Name").head.asString.get == "bitcoin02"
                  ).values
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
            parse(bridgeNetwork._1)
              .map(x =>
                (((x.asArray.get.head \\ "Containers").head.asObject.map { x =>
                  x.filter(x =>
                    (x._2 \\ "Name").head.asString.get == "bitcoin01"
                  ).values
                    .head
                }).get \\ "IPv4Address").head.asString.get
                  .split("/")
                  .head
              )
          )
          // print IP BTC 01
          _ <- IO.println("ipBitcoin01: " + ipBitcoin01)
          // add node
          _ <- process
            .ProcessBuilder(DOCKER_CMD, addNode(1, ipBitcoin02, 18444): _*)
            .spawn[IO]
            .use { getText }
          _ <- process
            .ProcessBuilder(DOCKER_CMD, addNode(2, ipBitcoin01, 18444): _*)
            .spawn[IO]
            .use { getText }
          _ <- initUserBitcoinWallet
          newAddress <- getNewAddress
          _ <- generateToAddress(1, 101, newAddress)
          _ <- mintToplBlock(1, 1)
        } yield ()).unsafeToFuture()
      }

      override def afterAll() = {
        fiber.cancel.void.unsafeToFuture()
      }
    }

  val cleanupDir = FunFixture[Unit](
    setup = { _ =>
      try {
        Files.delete(Paths.get(userWalletDb(1)))
      } catch {
        case _: Throwable => ()
      }
      try {
        Files.delete(Paths.get(userWalletMnemonic(1)))
      } catch {
        case _: Throwable => ()
      }
      try {
        Files.delete(Paths.get(userWalletJson(1)))
      } catch {
        case _: Throwable => ()
      }
      try {
        Files.delete(Paths.get(userWalletDb(2)))
      } catch {
        case _: Throwable => ()
      }
      try {
        Files.delete(Paths.get(userWalletMnemonic(2)))
      } catch {
        case _: Throwable => ()
      }
      try {
        Files.delete(Paths.get(userWalletJson(2)))
      } catch {
        case _: Throwable => ()
      }
      try {
        Files.delete(Paths.get(vkFile))
      } catch {
        case _: Throwable => ()
      }
      try {
        Files.delete(Paths.get("fundRedeemTx.pbuf"))
      } catch {
        case _: Throwable => ()
      }
      try {
        Files.delete(Paths.get("fundRedeemTxProved.pbuf"))
      } catch {
        case _: Throwable => ()
      }
      try {
        Files.delete(Paths.get("redeemTx.pbuf"))
      } catch {
        case _: Throwable => ()
      }
      try {
        Files.delete(Paths.get("redeemTxProved.pbuf"))
      } catch {
        case _: Throwable => ()
      }

    },
    teardown = { _ =>
      ()
    }
  )

  override def munitFixtures = List(startServer)

  cleanupDir.test("Bridge should correctly peg-in BTC") { _ =>
    info"Bridge should correctly peg-in BTC" >> successfulPegin()
  }
  cleanupDir.test("Bridge should fail correctly when user does not send BTC") {
    _ =>
      info"Bridge should fail correctly when user does not send BTC" >> failedPeginNoDeposit()
  }
  cleanupDir.test("Bridge should fail correctly when tBTC not minted") { _ =>
    info"Bridge should fail correctly when tBTC not minted" >> failedPeginNoMint()
  }
  cleanupDir.test("Bridge should fail correctly when tBTC not redeemed") { _ =>
    info"Bridge should fail correctly when tBTC not redeemed" >> failedRedemption()
  }

  cleanupDir.test(
    "Bridge should correctly go back from PeginSessionWaitingForEscrowBTCConfirmation"
  ) { _ =>
    info"Bridge should correctly go back from PeginSessionWaitingForEscrowBTCConfirmation" >> failedPeginNoDepositWithReorg()
  }

  cleanupDir.test(
    "Bridge should correctly go back from PeginSessionWaitingForClaimBTCConfirmation"
  ) { _ =>
    info"Bridge should correctly go back from PeginSessionWaitingForClaimBTCConfirmation" >> successfulPeginWithClaimError()
  }

  cleanupDir.test(
    "Bridge should correctly retry if claim does not succeed"
  ) { _ =>
    info"Bridge should correctly retry if claim does not succeed" >> successfulPeginWithClaimErrorRetry()
  }

  // FIXME: Re-enable this test
  // cleanupDir.test(
  //   "Bridge should correctly go back to minting if there is a reorg"
  // ) { _ =>
  //   info"Bridge should correctly go back to minting if there is a reorg" >> failedMintingReorgModule()
  // }

}
