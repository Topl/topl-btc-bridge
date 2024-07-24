package co.topl.bridge.consensus.modules

import munit.CatsEffectSuite 
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.core.config.NetworkParameters
import org.bitcoins.core.api.tor.Socks5ProxyParams
import org.bitcoins.rpc.config.{BitcoindAuthCredentials, BitcoindInstanceRemote}
import org.bitcoins.core.config.RegTest
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import org.bitcoins.core.currency.SatoshisLong


import java.net.URI
import org.apache.pekko.actor.ActorSystem
import co.topl.bridge.consensus.utils.BitcoinUtils._
import scodec.bits.ByteVector
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.crypto.ECPrivateKey
import java.security.MessageDigest
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.currency.Bitcoins
import co.topl.bridge.consensus.BTCWaitExpirationTime
import org.bitcoins.core.protocol.script.ScriptSignature
import org.bitcoins.core.protocol.script.NonStandardScriptSignature
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.crypto.ECDigitalSignature
import org.bitcoins.crypto.HashType
import org.bitcoins.core.protocol.script.P2WSHWitnessV0
import org.bitcoins.core.protocol.script.RawScriptPubKey
import org.bitcoins.core.wallet.fee.{SatoshisPerVirtualByte, SatoshisPerByte}
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.core.protocol.script.WitnessScriptPubKey
import org.bitcoins.core.script.constant.OP_0
import org.bitcoins.core.util.BitcoinScriptUtil
import org.bitcoins.core.util.BytesUtil
import org.bitcoins.crypto.CryptoUtil


class FeeEstimationSpec extends CatsEffectSuite {
  implicit val system: ActorSystem = ActorSystem("System")
    /**
     * Connection to the bitcoind RPC server instance
     *
     * @param network     Parameters of a given network to be used
     * @param host        The host to connect to the bitcoind instance
     * @param credentials rpc credentials
     * @param proxyParams proxy parameters
     * @return
     */
    def remoteConnection(
      network:     NetworkParameters,
      host:        String,
      credentials: BitcoindAuthCredentials,
      proxyParams: Option[Socks5ProxyParams] = None,
      port:        Option[Int] = None,
      rpcPort:     Option[Int] = None
    ): BitcoindRpcClient = BitcoindRpcClient(
      BitcoindInstanceRemote(
        network = network,
        uri = new URI(s"$host:${if (port.isDefined) port.get else network.port}"),
        rpcUri = new URI(s"$host:${if (rpcPort.isDefined) rpcPort.get else network.rpcPort}"),
        authCredentials = credentials,
        proxyParams = proxyParams
      )
    )

  val TestWallet = "test"
  val bitcoind = new Fixture[BitcoindRpcClient]("bitcoind") {
    val credentials = BitcoindAuthCredentials.PasswordBased("bitcoin", "password")

    val bitcoindInstance: BitcoindRpcClient = remoteConnection(RegTest, "http://localhost", credentials)
    def apply() = bitcoindInstance

    override def beforeAll(): Unit = {
      println("beforeall")
      Await.result(
        bitcoindInstance.createWallet(TestWallet, descriptors = true)
        .recover(_ => ()), // In case wallet already exists
        5.seconds)
      val addr = Await.result(
        bitcoindInstance.getNewAddress(walletName = TestWallet),
        5.seconds)
      Await.result(
        bitcoindInstance.generateToAddress(101, addr),
        5.seconds)
      println("wallet initialized")
    }
  }
  override def munitFixtures = List(bitcoind)

  test("Fee Estimation > Verify specified Fee Rate is used in reclaim transaction") {
    implicit val btcWaitExpirationTime: BTCWaitExpirationTime = new BTCWaitExpirationTime(1000)
    val bitcoindInstance = bitcoind()
    val initialFundsUtxo = Await.result(bitcoindInstance.listUnspent(walletName = TestWallet), 5.seconds).head
    val dummyUserPrivKey = ECPrivateKey.freshPrivateKey
    val dummyBridgeKey = ECPublicKey.dummy
    val dummyHash = ByteVector(MessageDigest.getInstance("SHA-256").digest("secret".getBytes))
    val escrowScript = buildScriptAsm(dummyUserPrivKey.publicKey, dummyBridgeKey, dummyHash, btcWaitExpirationTime.underlying)
    val scriptAsm = BytesUtil.toByteVector(escrowScript)
    val scriptHash = CryptoUtil.sha256(scriptAsm)
    val push_op = BitcoinScriptUtil.calculatePushOp(dummyHash)
    val escrowAddr = Bech32Address
    .apply(
      WitnessScriptPubKey
        .apply(
          Seq(OP_0) ++
            push_op ++
            Seq(ScriptConstant.fromBytes(scriptHash.bytes))
        ),
      RegTest
    )

    val desiredAmt = Bitcoins(initialFundsUtxo.amount.toBigDecimal - 10)
    println("desired amount: ", desiredAmt)


    val DesiredFeeRate = SatoshisPerByte(25.satoshis)

    val fee = estimateBtcReclaimFee(desiredAmt, DesiredFeeRate, RegTest)
    println("calculated fee: ", fee)

    val toSend = Bitcoins((desiredAmt.satoshis + fee.satoshis).satoshis)

    println("input amount: ", initialFundsUtxo.amount)
    println("escrow amount: ", toSend)

    val escrowTx = Await.result(bitcoindInstance.createRawTransaction(
      Vector(TransactionInput.fromTxidAndVout(initialFundsUtxo.txid, UInt32(initialFundsUtxo.vout))),
      Map(escrowAddr -> toSend) // any excess goes to fee
    ), 5.seconds)

    val escrowTxSigned = Await.result(
      bitcoindInstance.signRawTransactionWithWallet(escrowTx, walletName = TestWallet), 5.seconds
    ).hex
    
    val txId = Await.result(
      bitcoindInstance.sendRawTransaction(escrowTxSigned, 0), 5.seconds
    )
    val mineAddr = Await.result(
      bitcoindInstance.getNewAddress(walletName = TestWallet),
      5.seconds)
    Await.result(bitcoindInstance.generateToAddress(1001, mineAddr), 30.seconds) // mining blocks

    // Reclaim from escrow address
    val sequence: UInt32 = UInt32(1000L & TransactionConstants.sequenceLockTimeMask.toLong)
    val reclaimInput = TransactionInput(TransactionOutPoint(txId, 0), ScriptSignature.empty, sequence)
    val reclaimAddr = Await.result(
      bitcoindInstance.getNewAddress(walletName = TestWallet),
      5.seconds)
    val reclaimOutputs = Map(reclaimAddr -> desiredAmt) // the excess should equal to fee
    val unprovenReclaimTx = Await.result(bitcoindInstance.createRawTransaction(Vector(reclaimInput), reclaimOutputs), 5.seconds)
    val reclaimSerialized = serializeForSignature(unprovenReclaimTx, toSend, escrowScript)
    val reclaimSignableBytes = CryptoUtil.doubleSHA256(reclaimSerialized)
    val userSig = NonStandardScriptSignature.fromAsm(
      Seq(
        ScriptConstant(ECDigitalSignature(dummyUserPrivKey.sign(reclaimSignableBytes).bytes ++ ByteVector.fromByte(HashType.sigHashAll.byte)).hex)
      )
    )
    val witTx = WitnessTransaction
      .toWitnessTx(unprovenReclaimTx)
      .updateWitness(
        0,
        P2WSHWitnessV0(
          RawScriptPubKey(escrowScript),
          userSig
        )
      )
    println("actual vsize before broadcast: ", witTx.vsize)
    val reclaimTxId = Await.result(
      bitcoindInstance.sendRawTransaction(witTx, 0), 5.seconds
    )
    val txDetails = Await.result(bitcoindInstance.getTransaction(reclaimTxId, walletName = TestWallet), 5.seconds).hex
    val ActualFeeRate = SatoshisPerByte.calc(toSend, txDetails)
    println("actual fee rate: ", ActualFeeRate)
    println("actual vsize: ", txDetails.vsize)
    println("actual fee: ", toSend - txDetails.totalOutput)
    assertEquals(ActualFeeRate, DesiredFeeRate)
  }

}