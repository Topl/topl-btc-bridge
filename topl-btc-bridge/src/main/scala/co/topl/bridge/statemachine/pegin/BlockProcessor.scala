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
    val preimage = attestation.responses.head.getOr.right.getDigest.preimage
    new String(
      preimage.input.toByteArray
    )
  }

  def process[F[_]](
      block: Either[BitcoinBlockSync, BifrostMonitor.BifrostBlockSync]
  ): fs2.Stream[F, BlockchainEvent] = block match {
    case Left(b) =>
      fs2.Stream(NewBTCBlock(b.height)) ++ fs2.Stream(
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
              output.scriptPubKey,
              transaction.txIdBE.hex,
              vout.toLong,
              output.value
            )
          }
        ): _*
      )
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
}
