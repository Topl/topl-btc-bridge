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

  case class InputData(
      LockAddress: String,
      Type: String,
      Id: Option[String],
      Fungibility: Option[String],
      TokenSupply: Option[String],
      QuantDescr: Option[String],
      Value: Int,
      TxoAddress: Option[String],
      FixedSeries: Option[String]
  )

  def parseInput(input: String): List[InputData] = {
    val blocks = input.split("\n\n").toList // Split input into blocks
    blocks.map { block =>
      val lines = block.split("\n").map(_.trim).toList
      val dataMap = lines.map { line =>
        val Array(key, value) = line.split(":", 2).map(_.trim)
        key -> value
      }.toMap

      InputData(
        LockAddress = dataMap("LockAddress"),
        Type = dataMap("Type"),
        Id = dataMap.get("Id"),
        Fungibility = dataMap.get("Fungibility"),
        TokenSupply = dataMap.get("Token-Supply"),
        QuantDescr = dataMap.get("Quant-Descr."),
        Value = dataMap("Value").toInt,
        TxoAddress = dataMap.get("TxoAddress"),
        FixedSeries = dataMap.get("Fixed-Series")
      )
    }
  }

  def extractIds(input: String): (String, String) = {
    val dataList = parseInput(input)
    val seriesConstructorId =
      dataList.filter(_.Type == "Series Constructor").flatMap(_.Id)
    val groupConstructorId =
      dataList.filter(_.Type == "Group Constructor").flatMap(_.Id)

    (groupConstructorId.mkString, seriesConstructorId.mkString)
  }

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

  val CS_CMD = Option(System.getenv("CI"))
    .map(_ => "/home/runner/work/topl-btc-bridge/topl-btc-bridge/cs")
    .getOrElse("cs")

  val csParams = Seq(
    "launch",
    "-r",
    "https://s01.oss.sonatype.org/content/repositories/releases",
    "co.topl:brambl-cli_2.13:2.0.0-beta5",
    "--"
  )

  def userWalletDb(id: Int) = "user-wallet" + f"$id%02d" + ".db"

  def userWalletMnemonic(id: Int) = "user-wallet-mnemonic" + f"$id%02d" + ".txt"

  def userWalletJson(id: Int) = "user-wallet" + f"$id%02d" + ".json"

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

  def addSecret(id: Int) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "add-secret",
        "--walletdb",
        userWalletDb(id),
        "--secret",
        secretMap(id),
        "--digest",
        "sha256"
      ): _*
    )
    .spawn[IO]

  // brambl-cli wallet init --network private --password password --newwalletdb user-wallet.db --mnemonicfile user-wallet-mnemonic.txt --output user-wallet.json
  def initUserWallet(id: Int) = process
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
        userWalletDb(id),
        "--mnemonicfile",
        userWalletMnemonic(id),
        "--output",
        userWalletJson(id)
      ): _*
    )
    .spawn[IO]

  def getCurrentUtxosFromAddress(id: Int, address: String) = process
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
        userWalletDb(id),
        "--from-address",
        address
      ): _*
    )
    .spawn[IO]

  def templateFromSha(sha256: String, min: Long, max: Long) =
    s"""threshold(1, sha256($sha256) and height($min, $max))"""

  val secretMap = Map(1 -> "topl-secret", 2 -> "topl-secret01")

  val shaSecretMap = Map(
    1 -> "ee15b31e49931db6551ed8a82f1422ce5a5a8debabe8e81a724c88f79996d0df",
    2 -> "b46478c2553d2972c4a79172f7b468b422c6c516a980340acf83508d478504c3"
  )

  // brambl-cli templates add --walletdb user-wallet.db --template-name redeemBridge --lock-template
  def addTemplate(id: Int, sha256: String, min: Long, max: Long) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "templates",
        "add",
        "--walletdb",
        userWalletDb(id),
        "--template-name",
        "redeemBridge" + f"$id%02d",
        "--lock-template",
        templateFromSha(sha256, min, max)
      ): _*
    )
    .spawn[IO]

  // brambl-cli wallet import-vks --walletdb user-wallet.db --input-vks key.txt --fellowship-name bridge --template-name redeemBridge -w password -k user-wallet.json
  def importVks(id: Int) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "import-vks",
        "--walletdb",
        userWalletDb(id),
        "--input-vks",
        vkFile,
        "--fellowship-name",
        "bridge",
        "--template-name",
        "redeemBridge" + f"$id%02d",
        "-w",
        "password",
        "-k",
        userWalletJson(id)
      ): _*
    )
    .spawn[IO]

  // brambl-cli wallet current-address --walletdb user-wallet.db
  def currentAddress(id: Int) = process
    .ProcessBuilder(
      CS_CMD,
      csParams ++ Seq(
        "wallet",
        "current-address",
        "--walletdb",
        userWalletDb(id)
      ): _*
    )
    .spawn[IO]

  // brambl-cli simple-transaction create --from-fellowship nofellowship --from-template genesis --from-interaction 1 -t ptetP7jshHTzLLp81RbPkeHKWFJWeE3ijH94TAmiBRPTUTj2htC31NyEWU8p -w password -o genesisTx.pbuf -n private -a 10 -h  localhost --port 9084  --keyfile user-keyfile.json --walletdb user-wallet.db --fee 10 --transfer-token lvl
  def fundRedeemAddressTx(id: Int, redeemAddress: String) = process
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
        userWalletJson(id),
        "--walletdb",
        userWalletDb(id),
        "--fee",
        "10",
        "--transfer-token",
        "lvl"
      ): _*
    )
    .spawn[IO]

  // brambl-cli simple-transaction create --from-fellowship bridge --from-template redeemBridge -t ptetP7jshHTzLLp81RbPkeHKWFJWeE3ijH94TAmiBRPTUTj2htC31NyEWU8p -w password -o redeemTx.pbuf -n private -a 10 -h  localhost --port 9084  --keyfile user-keyfile.json --walletdb user-wallet.db --fee 10 --transfer-token asset
  def redeemAddressTx(
      id: Int,
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
        "redeemBridge" + f"$id%02d",
        "-t",
        redeemAddress,
        "-w",
        "password",
        "-o",
        "redeemTx.pbuf",
        "-n",
        "private",
        "-a",
        amount.toString(),
        "-h",
        "localhost",
        "--port",
        "9084",
        "--keyfile",
        userWalletJson(id),
        "--walletdb",
        userWalletDb(id),
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
  def proveFundRedeemAddressTx(
      id: Int,
      fileToProve: String,
      provedFile: String
  ) =
    process
      .ProcessBuilder(
        CS_CMD,
        csParams ++ Seq(
          "tx",
          "prove",
          "-i",
          fileToProve, // "fundRedeemTx.pbuf",
          "--walletdb",
          userWalletDb(id),
          "--keyfile",
          userWalletJson(id),
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
    "bitcoin01",
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
    "bitcoin01",
    "bitcoin-cli",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "-regtest",
    "-rpcwallet=testwallet",
    "getnewaddress"
  )
  def generateToAddress(nodeId: Int, blocks: Int, address: String) = Seq(
    "exec",
    "bitcoin" + f"$nodeId%02d",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "generatetoaddress",
    blocks.toString,
    address
  )

  def setNetworkActive(nodeId: Int, state: Boolean) = Seq(
    "exec",
    "bitcoin" + f"${nodeId}%02d",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "setnetworkactive",
    state.toString
  )

  // exec bitcoin01 bitcoin-cli -regtest -rpcuser=bitcoin -rpcpassword=password addnode <ip>:<port> add
  def addNode(nodeId: Int, ip: String, port: Int) = Seq(
    "exec",
    "bitcoin" + f"${nodeId}%02d",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "addnode",
    s"$ip:$port",
    "add"
  )
  def forceConnection(nodeId: Int, ip: String, port: Int) = Seq(
    "exec",
    "bitcoin" + f"${nodeId}%02d",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "addnode",
    s"$ip:$port",
    "onetry"
  )

  // network inspect bridge
  def inspectBridge(networkName: String) =
    Seq("network", "inspect", networkName)

  // docker network ls
  val networkLs = Seq("network", "ls")

  def signTransaction(tx: String) = Seq(
    "exec",
    "bitcoin01",
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
    "bitcoin01",
    "bitcoin-cli",
    "-regtest",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "sendrawtransaction",
    signedTx
  )

  val extractGetTxId = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-cli",
    "-rpcuser=bitcoin",
    "-rpcpassword=password",
    "-regtest",
    "-rpcwallet=testwallet",
    "listunspent"
  )

  def createTx(txId: String, address: String, amount: BigDecimal) = Seq(
    "exec",
    "bitcoin01",
    "bitcoin-tx",
    "-regtest",
    "-create",
    s"in=$txId:0",
    s"outaddr=$amount:$address"
  )

  def getText(p: fs2.io.process.Process[IO]) =
    p.stdout
      .through(fs2.text.utf8.decode)
      .compile
      .foldMonoid
      .map(_.trim)
  def getError(p: fs2.io.process.Process[IO]) =
    p.stderr
      .through(fs2.text.utf8.decode)
      .compile
      .foldMonoid

  // brambl-cli fellowships add --walletdb user-wallet.db --fellowship-name bridge
  def addFellowship(id: Int) = process
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
        userWalletDb(id),
        "--fellowship-name",
        "bridge"
      ): _*
    )
    .spawn[IO]

}
