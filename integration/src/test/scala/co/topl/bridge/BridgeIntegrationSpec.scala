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
    with FailedPeginNoDepositWithReorgModule {

  val DOCKER_CMD = "docker"

  override val munitIOTimeout = Duration(180, "s")

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
    // print network name
    _ <- IO.println("networkName: " + networkName)
    // inspect bridge
    bridgeNetwork <- process
      .ProcessBuilder(DOCKER_CMD, inspectBridge(networkName): _*)
      .spawn[IO]
      .use { getText }
    // print bridgeNetwork
  } yield bridgeNetwork

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
          _ <- IO.asyncForIO
            .both(
              IO.asyncForIO
                .start(
                  Main.run(
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
                      "10"
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
            parse(bridgeNetwork)
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
            parse(bridgeNetwork)
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
        } yield ()).unsafeToFuture()
      }

      override def afterAll() = {
        fiber.cancel.void.unsafeToFuture()
      }
    }

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

  override def munitFixtures = List(startServer)

  cleanupDir.test("Bridge should correctly peg-in BTC") { _ =>
    successfulPegin()
  }
  cleanupDir.test("Bridge should fail correctly when user does not send BTC") {
    _ =>
      failedPeginNoDeposit()
  }
  cleanupDir.test("Bridge should fail correctly when tBTC not minted") { _ =>
    failedPeginNoMint()
  }
  cleanupDir.test("Bridge should fail correctly when tBTC not redeemed") { _ =>
    failedRedemption()
  }

  cleanupDir.test(
    "Bridge should correctly go back from PeginSessionWaitingForEscrowBTCConfirmation"
  ) { _ =>
    failedPeginNoDepositWithReorg()
  }

}
