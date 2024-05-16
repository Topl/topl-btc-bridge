package co.topl

import cats.effect.IO
import co.topl.shared.MintingStatusRequest
import co.topl.shared.MintingStatusResponse
import co.topl.shared.StartPeginSessionRequest
import co.topl.shared.StartPeginSessionResponse
import co.topl.shared.SyncWalletRequest
import fs2.io.process
import io.circe.generic.auto._
import org.http4s.EntityDecoder
import org.http4s._
import org.http4s.circe._

package object bridge {

  object implicits {

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

  }

  val CS_CMD = "./cs"

  val csParams = Seq(
    "launch",
    "-r",
    "https://s01.oss.sonatype.org/content/repositories/releases",
    "co.topl:brambl-cli_2.13:2.0.0-beta5",
    "--"
  )

  val userWalletDb = "user-wallet.db"

  val userWalletMnemonic = "user-wallet-mnemonic.txt"

  val userWalletJson = "user-wallet.json"

  val vkFile = "key.txt"

  val getCurrentUtxos = process
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
        "data/topl-wallet.db"
      ): _*
    )
    .spawn[IO]

  val addSecret = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "add-secret",
        "--walletdb",
        userWalletDb,
        "--secret",
        "topl-secret",
        "--digest",
        "sha256"
      ): _*
    )
    .spawn[IO]

  // brambl-cli wallet init --network private --password password --newwalletdb user-wallet.db --mnemonicfile user-wallet-mnemonic.txt --output user-wallet.json
  val initUserWallet = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "init",
        "--network",
        "private",
        "--password",
        "password",
        "--newwalletdb",
        userWalletDb,
        "--mnemonicfile",
        userWalletMnemonic,
        "--output",
        userWalletJson
      ): _*
    )
    .spawn[IO]

  def getCurrentUtxosFromAddress(address: String) = process
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
        userWalletDb,
        "--from-address",
        address
      ): _*
    )
    .spawn[IO]

  def templateFromSha(sha256: String) =
    s"""threshold(1, sign(0) or sha256($sha256))"""

  val secret = "topl-secret"

  val sha256ToplSecret =
    "ee15b31e49931db6551ed8a82f1422ce5a5a8debabe8e81a724c88f79996d0df"

  // brambl-cli templates add --walletdb user-wallet.db --template-name redeemBridge --lock-template
  def addTemplate(sha256: String) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "templates",
        "add",
        "--walletdb",
        userWalletDb,
        "--template-name",
        "redeemBridge",
        "--lock-template",
        templateFromSha(sha256)
      ): _*
    )
    .spawn[IO]

  // brambl-cli wallet import-vks --walletdb user-wallet.db --input-vks key.txt --fellowship-name bridge --template-name redeemBridge -w password -k user-wallet.json
  val importVks = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "import-vks",
        "--walletdb",
        userWalletDb,
        "--input-vks",
        vkFile,
        "--fellowship-name",
        "bridge",
        "--template-name",
        "redeemBridge",
        "-w",
        "password",
        "-k",
        userWalletJson
      ): _*
    )
    .spawn[IO]

  // brambl-cli wallet current-address --walletdb user-wallet.db
  val currentAddress = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "current-address",
        "--walletdb",
        userWalletDb
      ): _*
    )
    .spawn[IO]

  // brambl-cli simple-transaction create --from-fellowship nofellowship --from-template genesis --from-interaction 1 -t ptetP7jshHTzLLp81RbPkeHKWFJWeE3ijH94TAmiBRPTUTj2htC31NyEWU8p -w password -o genesisTx.pbuf -n private -a 10 -h  localhost --port 9084  --keyfile user-keyfile.json --walletdb user-wallet.db --fee 10 --transfer-token lvl
  def fundRedeemAddressTx(redeemAddress: String) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "simple-transaction",
        "create",
        "--from-fellowship",
        "nofellowship",
        "--from-template",
        "genesis",
        "--from-interaction",
        "1",
        "--change-fellowship",
        "nofellowship",
        "--change-template",
        "genesis",
        "--change-interaction",
        "1",
        "-t",
        redeemAddress,
        "-w",
        "password",
        "-o",
        "fundRedeemTx.pbuf",
        "-n",
        "private",
        "-a",
        "10",
        "-h",
        "localhost",
        "--port",
        "9084",
        "--keyfile",
        userWalletJson,
        "--walletdb",
        userWalletDb,
        "--fee",
        "10",
        "--transfer-token",
        "lvl"
      ): _*
    )
    .spawn[IO]

  // brambl-cli simple-transaction create --from-fellowship bridge --from-template redeemBridge -t ptetP7jshHTzLLp81RbPkeHKWFJWeE3ijH94TAmiBRPTUTj2htC31NyEWU8p -w password -o redeemTx.pbuf -n private -a 10 -h  localhost --port 9084  --keyfile user-keyfile.json --walletdb user-wallet.db --fee 10 --transfer-token asset
  def redeemAddressTx(
      redeemAddress: String,
      amount: Long,
      groupId: String,
      seriesId: String
  ) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "simple-transaction",
        "create",
        "--from-fellowship",
        "bridge",
        "--from-template",
        "redeemBridge",
        "-t",
        redeemAddress,
        "-w",
        "password",
        "-o",
        "redeemTx.pbuf",
        "-n",
        "private",
        "-a",
        "10",
        "-h",
        "localhost",
        "--port",
        "9084",
        "--keyfile",
        userWalletJson,
        "--walletdb",
        userWalletDb,
        "--fee",
        "10",
        "--transfer-token",
        "asset",
        "--group-id",
        groupId,
        "--series-id",
        seriesId
      ): _*
    )
    .spawn[IO]

  // brambl-cli tx prove -i fundRedeemTx.pbuf --walletdb user-wallet.db --keyfile user-keyfile.json -w password -o fundRedeemTxProved.pbuf
  def proveFundRedeemAddressTx(fileToProve: String, provedFile: String) =
    process
      .ProcessBuilder(
        CS_CMD,
        csParams ++ Seq(
          "tx",
          "prove",
          "-i",
          fileToProve, // "fundRedeemTx.pbuf",
          "--walletdb",
          userWalletDb,
          "--keyfile",
          userWalletJson,
          "-w",
          "password",
          "-o",
          provedFile // "fundRedeemTxProved.pbuf"
        ): _*
      )
      .spawn[IO]

  // brambl-cli tx broadcast -i fundRedeemTxProved.pbuf -h localhost --port 9084
  def broadcastFundRedeemAddressTx(txFile: String) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "tx",
        "broadcast",
        "-i",
        txFile,
        "-h",
        "localhost",
        "--port",
        "9084"
      ): _*
    )
    .spawn[IO]

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
      CS_CMD,
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

}
