package co.topl.bridge.statemachine.pegin

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.brambl.codecs.AddressCodecs
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.models.box.Attestation
import co.topl.brambl.models.box.Value
import co.topl.brambl.monitoring.BifrostMonitor
import co.topl.brambl.monitoring.BitcoinMonitor.BitcoinBlock
import co.topl.brambl.utils.Encoding
import co.topl.bridge.PeginSessionState
import co.topl.bridge.PeginSessionState.PeginSessionStateMintingTBTC
import co.topl.bridge.PeginSessionState.PeginSessionStateWaitingForBTC
import co.topl.bridge.PeginSessionState.PeginSessionWaitingForRedemption
import co.topl.bridge.managers.PeginSessionInfo
import co.topl.bridge.managers.PegoutSessionInfo
import co.topl.bridge.managers.SessionCreated
import co.topl.bridge.managers.SessionEvent
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.managers.SessionUpdated
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.bridge.managers.TransactionAlgebra
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.typelevel.log4cats.Logger
import quivr.models.Int128
import quivr.models.KeyPair

import java.util.Map.Entry
import java.util.concurrent.ConcurrentHashMap
import scala.util.Try

sealed trait PeginStateMachineState

case class WaitingForBTC(
    currentWalletIdx: Int,
    scriptAsm: String,
    escrowAddress: String,
    redeemAddress: String
) extends PeginStateMachineState
case class MintingTBTC(
    currentWalletIdx: Int,
    scriptAsm: String,
    redeemAddress: String,
    btcTxId: String,
    btcVout: Long,
    amount: Long
) extends PeginStateMachineState
case class WaitingForRedemption(
    currentWalletIdx: Int,
    scriptAsm: String,
    redeemAddress: String,
    btcTxId: String,
    btcVout: Long,
    utxoTxId: String,
    utxoIndex: Int
) extends PeginStateMachineState
case object WaitingForClaim extends PeginStateMachineState

sealed trait BifrostCurrencyUnit {
  val amount: Int128
}

case class Lvl(amount: Int128) extends BifrostCurrencyUnit
case class SeriesToken(id: String, amount: Int128) extends BifrostCurrencyUnit
case class GroupToken(id: String, amount: Int128) extends BifrostCurrencyUnit
case class AssetToken(groupId: String, seriesId: String, amount: Int128)
    extends BifrostCurrencyUnit

sealed trait BlockchainEvent

case class BTCFundsWithdrawn(txId: String, vout: Long) extends BlockchainEvent
case class BTCFundsDeposited(
    scriptPubKey: ScriptPubKey,
    txId: String,
    vout: Long,
    amount: CurrencyUnit
) extends BlockchainEvent
case class BifrostFundsDeposited(
    address: String,
    utxoTxId: String,
    utxoIndex: Int,
    amount: BifrostCurrencyUnit
) extends BlockchainEvent
case class BifrostFundsWithdrawn(
    txId: String,
    txIndex: Int,
    secret: String,
    amount: BifrostCurrencyUnit
) extends BlockchainEvent

case class FSMTransitionTo[F[_]](
    prevState: PeginStateMachineState,
    nextState: PeginStateMachineState,
    effect: F[Unit]
)

class PeginStateMachine[F[_]: Async: Logger](
    sessionManager: SessionManagerAlgebra[F],
    waitingBTCForBlock: WaitingBTCOps[F],
    waitingForRedemptionOps: WaitingForRedemptionOps[F],
    toplKeypair: KeyPair,
    fromFellowship: String,
    fromTemplate: String,
    mintingFee: Long,
    toplWalletAlgebra: ToplWalletAlgebra[F],
    transactionAlgebra: TransactionAlgebra[F],
    utxoAlgebra: GenusQueryAlgebra[F],
    feePerByte: Long,
    map: ConcurrentHashMap[String, PeginStateMachineState]
) {
  import org.typelevel.log4cats.syntax._

  private def toCurrencyUnit(value: Value.Value) = if (value.isLvl)
    Lvl(value.lvl.get.quantity)
  else if (value.isSeries)
    SeriesToken(
      Encoding.encodeToBase58(
        value.series.get.seriesId.value.toByteArray()
      ),
      value.series.get.quantity
    )
  else if (value.isGroup)
    GroupToken(
      Encoding.encodeToBase58(
        value.group.get.groupId.value.toByteArray()
      ),
      value.group.get.quantity
    )
  else
    AssetToken(
      Encoding.encodeToBase58(
        value.asset.get.groupId.get.value.toByteArray()
      ),
      Encoding.encodeToBase58(
        value.asset.get.seriesId.get.value
          .toByteArray()
      ),
      value.asset.get.quantity
    )

  private def extractFromToplTx(proof: Attestation): String = {
    // The following is possible because we know the exact structure of the attestation
    val attestation = proof.getPredicate
    val preimage = attestation.responses.head.getOr.right.getDigest.preimage
    new String(
      preimage.input.toByteArray
    )
  }

  def blockProcessor(
      block: Either[BitcoinBlock, BifrostMonitor.BifrostBlockSync]
  ): fs2.Stream[F, BlockchainEvent] = block match {
    case Left(b) =>
      fs2.Stream(
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
      fs2.Stream(
        b.block.transactions.flatMap(transaction =>
          transaction.inputs.map { input =>
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

  def handleBlockchainEventInContext(blockchainEvent: BlockchainEvent) = {
    import scala.jdk.CollectionConverters._
    (for {
      entry <- fs2.Stream[F, Entry[String, PeginStateMachineState]](
        map.entrySet().asScala.toList: _*
      )
      sessionId = entry.getKey
      currentState = entry.getValue
    } yield handleBlockchainEvent(currentState, blockchainEvent, feePerByte)
      .map(x =>
        info"Transitioning session $sessionId from ${currentState
            .getClass()
            .getSimpleName()} to ${x.nextState.getClass().getSimpleName()}" >>
          processTransition(sessionId, x)
      )).collect({ case Some(value) =>
      info"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()}" >> value
    })

  }

  def handleBlockchainEvent(
      currentState: PeginStateMachineState,
      blockchainEvent: BlockchainEvent,
      feePerByte: Long
  ): Option[FSMTransitionTo[F]] =
    (currentState, blockchainEvent) match {
      case (
            WaitingForRedemption(
              currentWalletIdx,
              scriptAsm,
              _,
              btcTxId,
              btcVout,
              utxoTxId,
              utxoIndex
            ),
            BifrostFundsWithdrawn(txId, txIndex, secret, amount)
          ) =>
        if (utxoTxId == txId && utxoIndex == txIndex) {
          import co.topl.brambl.syntax._
          val claimingCommand =
            Async[F]
              .start(
                waitingForRedemptionOps.startClaimingProcess(
                  secret,
                  currentWalletIdx,
                  btcTxId,
                  btcVout,
                  scriptAsm, // scriptAsm,
                  amount.amount.toLong, // amount,
                  feePerByte
                )
              )
              .void
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForClaim,
              claimingCommand
            )
          )
        } else None
      case (
            WaitingForClaim,
            BTCFundsWithdrawn(_, _)
          ) =>
        None // FIXME: complete this
      case (
            MintingTBTC(
              currentWalletIdx,
              scriptAsm,
              redeemAddress,
              btcTxId,
              btcVout,
              _
            ),
            BifrostFundsDeposited(address, utxoTxId, utxoIndex, _)
          ) =>
        if (redeemAddress == address) {
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForRedemption(
                currentWalletIdx,
                scriptAsm,
                redeemAddress,
                btcTxId,
                btcVout,
                utxoTxId,
                utxoIndex
              ),
              Sync[F].unit
            )
          )
        } else None
      case (
            WaitingForBTC(
              currentWalletIdx,
              scriptAsm,
              escrowAddress,
              redeemAddress
            ),
            BTCFundsDeposited(scriptPubKey, txId, vout, amount)
          ) =>
        val bech32Address = Bech32Address.fromString(escrowAddress)
        if (scriptPubKey == bech32Address.scriptPubKey) {
          val mintingCommand = Async[F]
            .start(
              waitingBTCForBlock.startMintingProcess(
                fromFellowship,
                fromTemplate,
                redeemAddress,
                toplKeypair,
                amount.satoshis.toLong,
                toplWalletAlgebra,
                transactionAlgebra,
                utxoAlgebra,
                mintingFee
              )
            )
            .void
          Some(
            FSMTransitionTo(
              currentState,
              MintingTBTC(
                currentWalletIdx,
                scriptAsm,
                redeemAddress,
                txId,
                vout,
                amount.satoshis.toLong
              ),
              mintingCommand
            )
          )
        } else
          None
      case (
            _: WaitingForRedemption,
            _
          ) =>
        None // No transition
      case (
            WaitingForClaim,
            _
          ) =>
        None // No transition
      case (_: MintingTBTC, _) =>
        None // No transition
      case (
            _: WaitingForBTC,
            _
          ) =>
        None // No transition
    }

  private def fsmStateToSessionState(
      peginStateMachineState: PeginStateMachineState
  ): PeginSessionState = peginStateMachineState match {
    case _: MintingTBTC          => PeginSessionStateMintingTBTC
    case _: WaitingForBTC        => PeginSessionStateWaitingForBTC
    case _: WaitingForRedemption => PeginSessionWaitingForRedemption
    case WaitingForClaim         => PeginSessionStateWaitingForBTC
  }

  def processTransition(sessionId: String, transition: FSMTransitionTo[F]) =
    Sync[F].delay(map.replace(sessionId, transition.nextState)) >>
      sessionManager.updateSession(
        sessionId,
        _.copy(mintingBTCState = fsmStateToSessionState(transition.nextState))
      ) >> transition.effect

  def innerStateConfigurer(
      sessionEvent: SessionEvent
  ): F[Unit] = {
    sessionEvent match {
      case SessionCreated(sessionId, psi: PeginSessionInfo) =>
        info"New session created, waiting for funds at ${psi.escrowAddress}" >>
          Sync[F].delay(
            map.put(
              sessionId,
              WaitingForBTC(
                psi.currentWalletIdx,
                psi.scriptAsm,
                psi.escrowAddress,
                psi.redeemAddress
              )
            )
          )
      case SessionUpdated(_, _: PeginSessionInfo) =>
        Sync[F].unit
      case SessionCreated(_, _: PegoutSessionInfo) =>
        Sync[F].unit // TODO: complete for pegout
      case SessionUpdated(_, _: PegoutSessionInfo) =>
        Sync[F].unit // TODO: complete for pegout
    }
  }

}
