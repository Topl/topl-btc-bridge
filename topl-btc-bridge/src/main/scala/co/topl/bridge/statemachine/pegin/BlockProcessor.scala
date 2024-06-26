package co.topl.bridge.statemachine.pegin

import co.topl.brambl.codecs.AddressCodecs
import co.topl.brambl.models.box.Attestation
import co.topl.brambl.monitoring.BifrostMonitor
import co.topl.brambl.monitoring.BitcoinMonitor.BitcoinBlockSync
import co.topl.brambl.utils.Encoding

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
    var toplHeight = initialToplHeight // FIXME: This will be used for the topl reorgs
    var ascending = false
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
                output.scriptPubKey,
                transaction.txIdBE.hex,
                vout.toLong,
                output.value
              )
            }
          ): _*
        )
        val transactions =
          if (b.height == (btcHeight + 1)) { // going up as expected, include all transaction
            btcHeight = b.height
            ascending = true
            allTransactions
          } else if (b.height == (btcHeight - 1)) { // going down by one, we ommit transactions
            ascending = false
            fs2.Stream()
          } else if (b.height > (btcHeight + 1)) { // we went up by more than one
            ascending = true
            fs2.Stream(
              SkippedBTCBlock(b.height)
            )
          } else if (b.height < (btcHeight - 1)) { // we went down by more than one, we ommit transactions
            ascending = false
            fs2.Stream()
          } else {
            // we stayed the same
            if (ascending) {
              // if we are ascending, it means the current block was just unapplied
              // we don't pass the transactions that we have already seen
              ascending = false
              fs2.Stream()
            } else {
              // if we are descending, it means the current block was just applied
              // we need to pass all transactions
              ascending = true
              allTransactions
            }
          }
        fs2.Stream(NewBTCBlock(b.height)) ++ transactions
      case Right(b) =>
        fs2.Stream(NewToplBlock(b.height)) ++ fs2.Stream(
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
    }
    processAux

  }

}
