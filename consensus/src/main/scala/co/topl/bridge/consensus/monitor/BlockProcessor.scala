package co.topl.bridge.consensus.monitor

import co.topl.brambl.codecs.AddressCodecs
import co.topl.brambl.models.box.Attestation
import co.topl.brambl.monitoring.BifrostMonitor
import co.topl.brambl.monitoring.BitcoinMonitor.BitcoinBlockSync
import co.topl.brambl.utils.Encoding
import co.topl.bridge.consensus.persistence._

import scala.util.Try

object BlockProcessor {

  private def extractFromToplTx(proof: Attestation): String = {
    // The following is possible because we know the exact structure of the attestation
    val attestation = proof.getPredicate
    val preimage = attestation.responses.head.getAnd.left.getDigest.preimage
    new String(
      preimage.input.toByteArray
    )
  }

  def process[F[_]](
      initialBTCHeight: Int,
      initialToplHeight: Long
  ): Either[BitcoinBlockSync, BifrostMonitor.BifrostBlockSync] => fs2.Stream[
    F,
    BlockchainEvent
  ] = {
    var btcHeight = initialBTCHeight
    var toplHeight =
      initialToplHeight
    var btcAscending = false
    var toplAscending = false
    def processAux[F[_]](
        block: Either[BitcoinBlockSync, BifrostMonitor.BifrostBlockSync]
    ): fs2.Stream[F, BlockchainEvent] = block match {
      case Left(b) =>
        val allTransactions = fs2.Stream(
          b.block.transactions.flatMap(transaction =>
            transaction.inputs.map(input =>
              BTCFundsWithdrawn(
                input.previousOutput.txIdBE.hex,
                input.previousOutput.vout.toLong
              )
            )
          ) ++ b.block.transactions.flatMap(transaction =>
            transaction.outputs.zipWithIndex.map { outputAndVout =>
              val (output, vout) = outputAndVout
              BTCFundsDeposited(
                b.height,
                output.scriptPubKey.asmHex,
                transaction.txIdBE.hex,
                vout.toLong,
                output.value
              )
            }
          ): _*
        )
        if (btcHeight == 0)
          btcHeight = b.height - 1
        val transactions =
          if (b.height == (btcHeight + 1)) { // going up as expected, include all transaction
            btcAscending = true
            fs2.Stream(NewBTCBlock(b.height)) ++ allTransactions
          } else if (b.height == (btcHeight - 1)) { // going down by one, we ommit transactions
            btcAscending = false
            fs2.Stream(NewBTCBlock(b.height))
          } else if (b.height > (btcHeight + 1)) { // we went up by more than one
            btcAscending = true
            fs2.Stream(
              SkippedBTCBlock(b.height)
            )
          } else if (b.height < (btcHeight - 1)) { // we went down by more than one, we ommit transactions
            btcAscending = false
            fs2.Stream()
          } else {
            // we stayed the same
            if (btcAscending) {
              // if we are ascending, it means the current block was just unapplied
              // we don't pass the transactions that we have already seen
              btcAscending = false
              fs2.Stream(NewBTCBlock(b.height))
            } else {
              // if we are descending, it means the current block was just applied
              // we need to pass all transactions
              btcAscending = true
              fs2.Stream(NewBTCBlock(b.height)) ++ allTransactions
            }
          }
        btcHeight = b.height
        transactions
      case Right(b) =>
        val allTransactions = fs2.Stream(
          b.block.transactions.flatMap(transaction =>
            transaction.inputs
              .filter(x => isLvlSeriesGroupOrAsset(x.value.value))
              .map { input =>
                BifrostFundsWithdrawn(
                  Encoding.encodeToBase58(input.address.id.value.toByteArray()),
                  input.address.index,
                  Try(extractFromToplTx(input.attestation))
                    .getOrElse(""), // TODO: Make this safer
                  toCurrencyUnit(input.value.value)
                )
              }
          ) ++ b.block.transactions.flatMap(transaction =>
            transaction.outputs.zipWithIndex.map { outputAndIdx =>
              val (output, idx) = outputAndIdx
              val bifrostCurrencyUnit = toCurrencyUnit(output.value.value)
              BifrostFundsDeposited(
                b.height,
                AddressCodecs.encodeAddress(output.address),
                Encoding.encodeToBase58(
                  transaction.transactionId.get.value.toByteArray()
                ),
                idx,
                bifrostCurrencyUnit
              )
            }
          ): _*
        )
        if (toplHeight == 0)
          toplHeight = b.height - 1
        val transactions =
          if (b.height == (toplHeight + 1)) { // going up as expected, include all transaction
            toplAscending = true
            fs2.Stream(NewToplBlock(b.height)) ++ allTransactions
          } else if (b.height == (toplHeight - 1)) { // going down by one, we ommit transactions
            toplAscending = false
            fs2.Stream(NewToplBlock(b.height))
          } else if (b.height > (toplHeight + 1)) { // we went up by more than one
            toplAscending = true
            fs2.Stream(
              SkippedToplBlock(b.height)
            )
          } else if (b.height < (toplHeight - 1)) { // we went down by more than one, we ommit transactions
            toplAscending = false
            fs2.Stream()
          } else {
            // we stayed the same
            if (toplAscending) {
              // if we are ascending, it means the current block was just unapplied
              // we don't pass the transactions that we have already seen
              toplAscending = false
              fs2.Stream(NewToplBlock(b.height))
            } else {
              // if we are descending, it means the current block was just applied
              // we need to pass all transactions
              toplAscending = true
              fs2.Stream(NewToplBlock(b.height)) ++ allTransactions
            }
          }
        toplHeight = b.height
        transactions
    }
    processAux

  }

}
