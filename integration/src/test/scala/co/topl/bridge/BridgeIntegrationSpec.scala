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

  test("Bridge should mint assets on the Topl network") {
    import io.circe._, io.circe.parser._
    assertIO(
      for {
        _ <- process
          .ProcessBuilder(DOCKER_CMD, createWallet: _*)
          .spawn[IO]
          .use { _.exitValue }
        newAddress <- process // we get the new address
          .ProcessBuilder(DOCKER_CMD, getNewaddress: _*)
          .spawn[IO]
          .use(getText)
        _ <- process
          .ProcessBuilder(DOCKER_CMD, generateToAddress(newAddress): _*)
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
      } yield (),
      ()
    )
  }
}
