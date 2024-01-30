import cats.data.Kleisli
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import co.topl.bridge.BridgeParamsDescriptor
import co.topl.bridge.ServerConfig
import co.topl.bridge.ToplBTCBridgeParamConfig
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.ConfirmRedemptionRequest
import co.topl.shared.ConfirmRedemptionResponse
import co.topl.shared.StartSessionRequest
import co.topl.shared.StartSessionResponse
import co.topl.shared.utils.KeyGenerationUtils
import io.circe.generic.auto._
import org.bitcoins.core.currency.BitcoinsInt
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.currency.SatoshisLong
import org.bitcoins.core.number.Int32
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.core.protocol.CompactSizeUInt
import org.bitcoins.core.protocol.script.EmptyScriptPubKey
import org.bitcoins.core.protocol.script.NonStandardScriptSignature
import org.bitcoins.core.protocol.script.P2WPKHWitnessSPKV0
import org.bitcoins.core.protocol.script.P2WSHWitnessV0
import org.bitcoins.core.protocol.script.RawScriptPubKey
import org.bitcoins.core.protocol.script.ScriptSignature
import org.bitcoins.core.protocol.script.WitnessScriptPubKey
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.protocol.transaction.TransactionInput
import org.bitcoins.core.protocol.transaction.TransactionOutPoint
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.protocol.transaction.WitnessTransaction
import org.bitcoins.core.script.bitwise.OP_EQUAL
import org.bitcoins.core.script.bitwise.OP_EQUALVERIFY
import org.bitcoins.core.script.constant.OP_0
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.script.constant.ScriptNumber
import org.bitcoins.core.script.constant.ScriptNumberOperation
import org.bitcoins.core.script.constant.ScriptToken
import org.bitcoins.core.script.control.OP_ELSE
import org.bitcoins.core.script.control.OP_ENDIF
import org.bitcoins.core.script.control.OP_NOTIF
import org.bitcoins.core.script.crypto.OP_CHECKSIG
import org.bitcoins.core.script.crypto.OP_CHECKSIGVERIFY
import org.bitcoins.core.script.crypto.OP_SHA256
import org.bitcoins.core.script.locktime.OP_CHECKSEQUENCEVERIFY
import org.bitcoins.core.script.splice.OP_SIZE
import org.bitcoins.core.util.BitcoinScriptUtil
import org.bitcoins.core.util.BytesUtil
import org.bitcoins.core.wallet.builder.StandardNonInteractiveFinalizer
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.core.wallet.utxo.ConditionalPath
import org.bitcoins.core.wallet.utxo.SegwitV0NativeInputInfo
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.crypto._
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.staticcontent.resourceServiceBuilder
import scodec.bits.ByteVector
import scopt.OParser

import java.util.UUID

object Main extends IOApp with BridgeParamsDescriptor {

  case class SessionInfo(
      bridgePKey: String,
      userPKey: String,
      secretHash: String,
      scriptAsm: String,
      address: String
  )

  var map = Map.empty[String, SessionInfo]

  // or(and(pk(A),older(1000)),and(pk(B),sha256(H)))
  def createDescriptor(
      bridgePKey: String,
      userPKey: String,
      secretHash: String
  ) =
    s"wsh(andor(pk($userPKey),older(1000),and_v(v:pk($bridgePKey),sha256($secretHash))))"

  def serializeForSignature(
      txTo: Transaction,
      inputAmount: CurrencyUnit, // amount in the output of the previous transaction (what we are spending)
      inputScript: Seq[ScriptToken]
  ): ByteVector = {
    val hashPrevouts: ByteVector = {
      val prevOuts = txTo.inputs.map(_.previousOutput)
      val bytes: ByteVector = BytesUtil.toByteVector(prevOuts)
      CryptoUtil.doubleSHA256(bytes).bytes // result is in little endian
    }

    val hashSequence: ByteVector = {
      val sequences = txTo.inputs.map(_.sequence)
      val littleEndianSeq =
        sequences.foldLeft(ByteVector.empty)(_ ++ _.bytes.reverse)
      CryptoUtil
        .doubleSHA256(littleEndianSeq)
        .bytes // result is in little endian
    }

    val hashOutputs: ByteVector = {
      val outputs = txTo.outputs
      val bytes = BytesUtil.toByteVector(outputs)
      CryptoUtil.doubleSHA256(bytes).bytes // result is in little endian
    }

    val scriptBytes = BytesUtil.toByteVector(inputScript)

    val i = txTo.inputs.head
    val serializationForSig: ByteVector =
      txTo.version.bytes.reverse ++ hashPrevouts ++ hashSequence ++
        i.previousOutput.bytes ++ CompactSizeUInt.calc(scriptBytes).bytes ++
        scriptBytes ++ inputAmount.bytes ++ i.sequence.bytes.reverse ++
        hashOutputs ++ txTo.lockTime.bytes.reverse ++ Int32(
          HashType.sigHashAll.num
        ).bytes.reverse
    serializationForSig
  }
  def getTxSignature(
      unsignedTx: Transaction,
      script: RawScriptPubKey,
      privateKey: String,
      inputAmount: CurrencyUnit
  ): ECDigitalSignature = {
    val serializedTxForSignature =
      serializeForSignature(unsignedTx, inputAmount, script.asm)
    val signableBytes = CryptoUtil.doubleSHA256(serializedTxForSignature)
    val signature = ECPrivateKey.fromHex(privateKey).sign(signableBytes.bytes)
    // append 1 byte hash type onto the end, per BIP-066
    ECDigitalSignature(
      signature.bytes ++ ByteVector.fromByte(HashType.sigHashAll.byte)
    )
  }

  def apiServices(btcNetwork: BitcoinNetworkIdentifiers) = HttpRoutes.of[IO] {
    case req @ POST -> Root / "start-session" =>
      implicit val startSessionRequestDecoder
          : EntityDecoder[IO, StartSessionRequest] =
        jsonOf[IO, StartSessionRequest]
      import io.circe.syntax._
      (for {
        req <- req.as[StartSessionRequest]
        sessionId <- IO(UUID.randomUUID().toString)
        password <- IO(sessionId)
        newKey <- KeyGenerationUtils.generateKey[IO](
          btcNetwork,
          sessionId + ".json",
          password
        )
        hash = ByteVector.fromHex(req.sha256).get
        asm <- IO(
          buildScriptAsm(
            ECPublicKey.fromHex(req.pkey),
            ECPublicKey.fromHex(newKey),
            hash,
            1000L
          )
        )
        scriptAsm = BytesUtil.toByteVector(asm)
        scriptHash = CryptoUtil.sha256(scriptAsm)
        push_op = BitcoinScriptUtil.calculatePushOp(hash)
        address = Bech32Address
          .apply(
            WitnessScriptPubKey
              .apply(
                Seq(OP_0) ++
                  push_op ++
                  Seq(ScriptConstant.fromBytes(scriptHash.bytes))
              ),
            btcNetwork.btcNetwork
          )
          .value
        sessionInfo = SessionInfo(
          newKey,
          req.pkey,
          req.sha256,
          scriptAsm.toHex,
          address
        )
        _ = map = map + (sessionId -> sessionInfo)
        resp <- Ok(
          StartSessionResponse(
            sessionId,
            scriptAsm.toHex,
            address,
            createDescriptor(newKey, req.pkey, req.sha256)
          ).asJson
        )
      } yield resp).handleErrorWith(e => {
        e.printStackTrace()
        BadRequest("Error")
      })
    case req @ POST -> Root / "confirm-redemption" =>
      implicit val confirmRedemptionRequestDecoder
          : EntityDecoder[IO, ConfirmRedemptionRequest] =
        jsonOf[IO, ConfirmRedemptionRequest]
      import io.circe.syntax._
      (for {
        req <- req.as[ConfirmRedemptionRequest]
        sessionInfo <- IO.fromOption(
          map.get(req.sessionID)
        )(new IllegalArgumentException("Invalid session ID"))
        inputAmount = req.amount.satoshis
        spentAmount = 11.bitcoins
        outpoint = TransactionOutPoint(
          DoubleSha256DigestBE.apply(req.inputTxId),
          UInt32(req.inputIndex)
        )
        // sequence = UInt32(1000L & TransactionConstants.sequenceLockTimeMask.toLong)
        inputs = Vector(
          TransactionInput.apply(outpoint, ScriptSignature.empty, UInt32.zero)
        )
        outputs = Vector(
          TransactionOutput(
            spentAmount,
            P2WPKHWitnessSPKV0.apply(ECPublicKey.freshPublicKey)
          )
        )
        builderResult = Transaction.newBuilder
          .++=(inputs)
          .++=(outputs)
          .result()
        feeRate = SatoshisPerVirtualByte(req.feePerByte.satoshi)
        changePrivKey = ECPrivateKey.freshPrivateKey
        changeSPK = P2WPKHWitnessSPKV0(pubKey = changePrivKey.publicKey)
        inputInfo = SegwitV0NativeInputInfo.apply(
          outpoint,
          inputAmount,
          P2WSHWitnessV0.apply(EmptyScriptPubKey),
          ConditionalPath.NoCondition
        )
        finalizer = StandardNonInteractiveFinalizer(
          Vector(inputInfo),
          feeRate,
          changeSPK
        )
        tx: Transaction = finalizer.buildTx(builderResult)
        _ = println(tx)
        srp = RawScriptPubKey.fromAsmHex(sessionInfo.scriptAsm)
        serializedTxForSignature =
          serializeForSignature(tx, inputAmount, srp.asm)
        signableBytes = CryptoUtil.doubleSHA256(serializedTxForSignature)
        signature <- KeyGenerationUtils.loadKeyAndSign[IO](
          btcNetwork,
          req.sessionID + ".json",
          req.sessionID,
          signableBytes.bytes
        )
        bridgeSig = NonStandardScriptSignature.fromAsm(
          Seq(
            ScriptConstant.fromBytes(
              ByteVector(req.secret.getBytes().padTo(32, 0.toByte))
            ),
            ScriptConstant(
              signature
            ), // signature of bridge
            OP_0
          )
        )
        txWit = WitnessTransaction
          .toWitnessTx(tx)
          .updateWitness(
            0,
            P2WSHWitnessV0(
              srp,
              bridgeSig
            )
          )
        resp <- Ok(
          ConfirmRedemptionResponse(
            txWit.hex
          ).asJson
        )
      } yield resp).handleErrorWith(e => {
        e.printStackTrace()
        BadRequest("Error")
      })
  }

  def buildScriptAsm(
      userPKey: ECPublicKey,
      bridgePKey: ECPublicKey,
      secretHash: ByteVector,
      relativeLockTime: Long
  ): Seq[ScriptToken] = {
    val pushOpsUser = BitcoinScriptUtil.calculatePushOp(userPKey.bytes)
    val pushOpsBridge = BitcoinScriptUtil.calculatePushOp(bridgePKey.bytes)
    val pushOpsSecretHash =
      BitcoinScriptUtil.calculatePushOp(secretHash)
    val pushOp32 =
      BitcoinScriptUtil.calculatePushOp(ScriptNumber.apply(32))

    val scriptOp =
      BitcoinScriptUtil.minimalScriptNumberRepresentation(
        ScriptNumber(relativeLockTime)
      )

    val scriptNum: Seq[ScriptToken] =
      if (scriptOp.isInstanceOf[ScriptNumberOperation]) {
        Seq(scriptOp)
      } else {
        val pushOpsLockTime =
          BitcoinScriptUtil.calculatePushOp(ScriptNumber(relativeLockTime))
        pushOpsLockTime ++ Seq(
          ScriptConstant(ScriptNumber(relativeLockTime).bytes)
        )
      }

    pushOpsUser ++ Seq(
      ScriptConstant.fromBytes(userPKey.bytes),
      OP_CHECKSIG,
      OP_NOTIF
    ) ++ pushOpsBridge ++ Seq(
      ScriptConstant.fromBytes(bridgePKey.bytes),
      OP_CHECKSIGVERIFY,
      OP_SIZE
    ) ++ pushOp32 ++ Seq(
      ScriptNumber.apply(32),
      OP_EQUALVERIFY,
      OP_SHA256
    ) ++ pushOpsSecretHash ++ Seq(
      ScriptConstant.fromBytes(secretHash),
      OP_EQUAL
    ) ++ Seq(OP_ELSE) ++ scriptNum ++ Seq(
      OP_CHECKSEQUENCEVERIFY,
      OP_ENDIF
    )

  }

  override def run(args: List[String]): IO[ExitCode] = {
    OParser.parse(parser, args, ToplBTCBridgeParamConfig()) match {
      case Some(config) =>
        runWithArgs(config)
      case None =>
        println("Invalid arguments")
        IO(ExitCode.Error)
    }
  }

  def runWithArgs(params: ToplBTCBridgeParamConfig): IO[ExitCode] = {

    val staticAssetsService = resourceServiceBuilder[IO]("/static").toRoutes
    (for {
      notFoundResponse <- Resource.make(
        NotFound(
          """<!DOCTYPE html>
          |<html>
          |<body>
          |<h1>Not found</h1>
          |<p>The page you are looking for is not found.</p>
          |<p>This message was generated on the server.</p>
          |</body>
          |</html>""".stripMargin('|'),
          headers.`Content-Type`(MediaType.text.html)
        )
      )(_ => IO.unit)
      app = {
        val router = Router.define(
          "/" -> apiServices(params.btcNetwork)
        )(default = staticAssetsService)

        Kleisli[IO, Request[IO], Response[IO]] { request =>
          router.run(request).getOrElse(notFoundResponse)
        }
      }
      logger =
        org.typelevel.log4cats.slf4j.Slf4jLogger.getLoggerFromName[IO]("App")
      _ <- EmberServerBuilder
        .default[IO]
        .withIdleTimeout(ServerConfig.idleTimeOut)
        .withHost(ServerConfig.host)
        .withPort(ServerConfig.port)
        .withHttpApp(app)
        .withLogger(logger)
        .build
    } yield {
      Right(
        s"Server started on ${ServerConfig.host}:${ServerConfig.port}"
      )
    }).allocated
      .map(_._1)
      .handleErrorWith { e =>
        IO {
          Left(e.getMessage)
        }
      } >> IO.never

  }
}
