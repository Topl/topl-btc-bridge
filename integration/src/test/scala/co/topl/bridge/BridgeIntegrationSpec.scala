package co.topl.bridge

import munit.CatsEffectSuite
import fs2.io.process
import cats.effect.IO

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
    "-rpcwallet=testwallet",
    "generatetoaddress",
    "generatetoaddress",
    "101",
    address
  )

  test("Bridge should mint assets on the Topl network") {
    assertIO(
      for {
        _ <- process
          .ProcessBuilder(DOCKER_CMD, createWallet: _*)
          .spawn[IO]
          .use { _.exitValue }
        newAddress <- process
          .ProcessBuilder(DOCKER_CMD, getNewaddress: _*)
          .spawn[IO]
          .use { process =>
            process.stdout
              .through(fs2.text.utf8Decode)
              .compile
              .foldMonoid
          }
        _ <- IO(println("New address: " + newAddress))
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(newAddress): _*)
          .spawn[IO]
          .use { process =>
            process.exitValue
          }
      } yield (),
      ()
    )
  }
}
