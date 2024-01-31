package co.topl.bridge

import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.number.Int32
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.CompactSizeUInt
import org.bitcoins.core.protocol.script.EmptyScriptPubKey
import org.bitcoins.core.protocol.script.P2WPKHWitnessSPKV0
import org.bitcoins.core.protocol.script.P2WSHWitnessV0
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.script.ScriptSignature
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.protocol.transaction.TransactionInput
import org.bitcoins.core.protocol.transaction.TransactionOutPoint
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.bitcoins.core.script.bitwise.OP_EQUAL
import org.bitcoins.core.script.bitwise.OP_EQUALVERIFY
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
import org.bitcoins.core.wallet.builder.SubtractFeeFromOutputsFinalizer
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.core.wallet.utxo.ConditionalPath
import org.bitcoins.core.wallet.utxo.SegwitV0NativeInputInfo
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.crypto._
import scodec.bits.ByteVector

object BitcoinUtils {

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

  def createRedeemingTx(
      inputTxId: String,
      inputTxVout: Int,
      inputAmount: Long,
      feePerByte: Long,
      destinationPubKey: ECPublicKey
  ) = {
    import org.bitcoins.core.currency.SatoshisLong
    val inputAmountSatoshis = inputAmount.satoshis
    val outpoint = TransactionOutPoint(
      DoubleSha256DigestBE.apply(inputTxId),
      UInt32(inputTxVout)
    )
    val inputs = Vector(
      TransactionInput.apply(outpoint, ScriptSignature.empty, UInt32.zero)
    )
    val outputs = Vector(
      TransactionOutput(
        inputAmountSatoshis,
        P2WPKHWitnessSPKV0.apply(destinationPubKey)
      )
    )
    val builderResult = Transaction.newBuilder
      .++=(inputs)
      .++=(outputs)
      .result()
    val feeRate = SatoshisPerVirtualByte(feePerByte.satoshis)
    val inputInfo = SegwitV0NativeInputInfo.apply(
      outpoint,
      inputAmountSatoshis,
      P2WSHWitnessV0.apply(EmptyScriptPubKey),
      ConditionalPath.NoCondition
    )
    val finalizer = SubtractFeeFromOutputsFinalizer(
      Vector(inputInfo),
      feeRate,
      Vector(ScriptPubKey.apply(P2WPKHWitnessSPKV0(pubKey = destinationPubKey).asm))
    )
    finalizer.buildTx(builderResult)
  }

}
