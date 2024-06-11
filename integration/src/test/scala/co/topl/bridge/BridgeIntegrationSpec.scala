package co.topl.bridge

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.kernel.Fiber
import munit.CatsEffectSuite

import java.nio.file.Files
import java.nio.file.Paths
import scala.concurrent.duration._
import munit.FutureFixture
import munit.AnyFixture

class BridgeIntegrationSpec
    extends CatsEffectSuite
    with SuccessfulPeginModule
    with FailedPeginNoDepositModule
    with FailedPeginNoMintModule
    with FailedRedemptionModule {

  val DOCKER_CMD = "docker"

  override val munitIOTimeout = Duration(180, "s")

  lazy val toplWalletDb =
    Option(System.getenv("TOPL_WALLET_DB")).getOrElse("topl-wallet.db")
  lazy val toplWalletJson =
    Option(System.getenv("TOPL_WALLET_JSON")).getOrElse("topl-wallet.json")

  val startServer: AnyFixture[Unit] =
    new FutureFixture[Unit]("server setup") {

      var fiber: Fiber[IO, Throwable, ExitCode] = _
      def apply() = fiber: Unit

      override def beforeAll() = {
        IO.asyncForIO
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
                    // "--topl-blocks-to-recover",
                    // "20"
                  )
                )
              ),
            IO.sleep(10.seconds)
          )
          .map { case (f, _) =>
            fiber = f
          }
          .void
          .unsafeToFuture()
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

}
