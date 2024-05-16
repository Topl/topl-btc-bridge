package co.topl.bridge

import munit.CatsEffectSuite

import java.nio.file.Files
import java.nio.file.Paths
import scala.concurrent.duration._

class BridgeIntegrationSpec
    extends CatsEffectSuite
    with SuccessfulPeginModule
    with FailedPeginNoDepositModule {

  val DOCKER_CMD = "docker"

  override val munitIOTimeout = Duration(180, "s")

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


  cleanupDir.test("Bridge should correctly peg-in BTC") { _ =>
    successfulPegin()
  }
  cleanupDir.test("Bridge should fail correctly when user does not send BTC") { _ =>
    failedPeginNoDeposit()
  }

}
