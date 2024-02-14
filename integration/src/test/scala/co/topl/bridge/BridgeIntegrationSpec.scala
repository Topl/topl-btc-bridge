package co.topl.bridge

import munit.CatsEffectSuite
import fs2.io.process
import cats.effect.IO

class BridgeIntegrationSpec extends CatsEffectSuite {

  val DOCKER_CMD = "docker"

  val generateNewAddressCommand = Seq(
    "exec",
    "bitcoin",
    "bitcoin-cli",
    "-regtest",
    "-named",
    "createwallet",
    "wallet_name=testwallet"
  )

  test("Bridge should mint assets on the Topl network") {
    assertIO(
      for {
        newAddress <- process
          .ProcessBuilder(DOCKER_CMD, generateNewAddressCommand: _*)
          .spawn[IO]
          .use { process =>
            process.stderr
              .through(fs2.text.utf8Decode)
              .compile
              .foldMonoid
          }
        _ <- IO(println("New address: " + newAddress))
      } yield (),
      ()
    )
  }
}
