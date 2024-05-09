package co.topl.bridge.managers

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.brambl.codecs.AddressCodecs
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.models.transaction.UnspentTransactionOutput
import co.topl.brambl.monitoring.BifrostMonitor
import co.topl.brambl.monitoring.BitcoinMonitor.BitcoinBlock
import co.topl.brambl.utils.Encoding
import co.topl.bridge.PeginSessionState
import co.topl.bridge.PeginSessionState.PeginSessionStateMintingTBTC
import co.topl.bridge.PeginSessionState.PeginSessionStateWaitingForBTC
import co.topl.bridge.PeginSessionState.PeginSessionWaitingForRedemption
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.typelevel.log4cats.Logger
import quivr.models.Int128
import quivr.models.KeyPair

import java.util.Map.Entry
import java.util.concurrent.ConcurrentHashMap

sealed trait PeginStateMachineState

case class WaitingForBTC(escrowAddress: String, redeemAddress: String)
    extends PeginStateMachineState
case class MintingTBTC(redeemAddress: String, amount: Long)
    extends PeginStateMachineState
case object WaitingForRedemption extends PeginStateMachineState
case object WaitingForClaim extends PeginStateMachineState

sealed trait BifrostCurrencyUnit

case class Lvl(amount: Int128) extends BifrostCurrencyUnit
case class SeriesToken(id: String, amount: Int128) extends BifrostCurrencyUnit
case class GroupToken(id: String, amount: Int128) extends BifrostCurrencyUnit
case class AssetToken(groupId: String, seriesId: String, amount: Int128)
    extends BifrostCurrencyUnit

sealed trait BlockchainEvent

case class BTCFundsWithdrawn(txId: String, vout: Long) extends BlockchainEvent
case class BTCFundsDeposited(scriptPubKey: ScriptPubKey, amount: CurrencyUnit)
    extends BlockchainEvent
case class BifrostFundsDeposited(address: String, amount: BifrostCurrencyUnit)
    extends BlockchainEvent
case class BifrostFundsWithdrawn(address: String, amount: BifrostCurrencyUnit)
    extends BlockchainEvent

case class FSMTransitionTo[F[_]](
    prevState: PeginStateMachineState,
    nextState: PeginStateMachineState,
    effect: F[Unit]
)

class PeginStateMachine[F[_]: Async: Logger](
    sessionManager: SessionManagerAlgebra[F],
    waitingBTCForBlock: WaitingBTCForBlock[F],
    toplKeypair: KeyPair,
    fromFellowship: String,
    fromTemplate: String,
    mintingFee: Long,
    toplWalletAlgebra: ToplWalletAlgebra[F],
    transactionAlgebra: TransactionAlgebra[F],
    utxoAlgebra: GenusQueryAlgebra[F],
    map: ConcurrentHashMap[String, PeginStateMachineState]
) {
  import org.typelevel.log4cats.syntax._

  private def toCurrencyUnit(output: UnspentTransactionOutput) = if (
    output.value.value.isLvl
  )
    Lvl(output.value.value.lvl.get.quantity)
  else if (output.value.value.isSeries)
    SeriesToken(
      Encoding.encodeToBase58(
        output.value.value.series.get.seriesId.value.toByteArray()
      ),
      output.value.value.series.get.quantity
    )
  else if (output.value.value.isGroup)
    GroupToken(
      Encoding.encodeToBase58(
        output.value.value.group.get.groupId.value.toByteArray()
      ),
      output.value.value.group.get.quantity
    )
  else
    AssetToken(
      Encoding.encodeToBase58(
        output.value.value.asset.get.groupId.get.value.toByteArray()
      ),
      Encoding.encodeToBase58(
        output.value.value.asset.get.seriesId.get.value
          .toByteArray()
      ),
      output.value.value.asset.get.quantity
    )

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
          transaction.outputs.map { output =>
            BTCFundsDeposited(output.scriptPubKey, output.value)
          }
        ): _*
      )
    case Right(b) =>
      fs2.Stream(
        b.block.transactions.flatMap(transaction =>
          transaction.inputs.map(input =>
            BTCFundsWithdrawn(
              Encoding.encodeToBase58(input.address.id.value.toByteArray()),
              input.address.index
            )
          )
        ) ++ b.block.transactions.flatMap(transaction =>
          transaction.outputs.map { output =>
            val bifrostCurrencyUnit = toCurrencyUnit(output)
            BifrostFundsDeposited(
              AddressCodecs.encodeAddress(output.address),
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
    } yield handleBlockchainEvent(currentState, blockchainEvent).map(x =>
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
      blockchainEvent: BlockchainEvent
  ): Option[FSMTransitionTo[F]] =
    (currentState, blockchainEvent) match {
      case (
            WaitingForRedemption,
            BifrostFundsWithdrawn(_, _)
          ) =>
        None // FIXME: complete this
      case (
            WaitingForClaim,
            BTCFundsWithdrawn(_, _)
          ) =>
        None // FIXME: complete this
      case (
            MintingTBTC(redeemAddress, _),
            BifrostFundsDeposited(address, _)
          ) =>
        if (redeemAddress == address) {
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForRedemption,
              Sync[F].unit
            )
          )
        } else None
      case (
            WaitingForBTC(escrowAddress, redeemAddress),
            BTCFundsDeposited(scriptPubKey, amount)
          ) =>
        val bech32Address = Bech32Address.fromString(escrowAddress)
        if (scriptPubKey == bech32Address.scriptPubKey) {
          val mintingCommand = Async[F].start(
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
          ).void
          Some(
            FSMTransitionTo(
              currentState,
              MintingTBTC(redeemAddress, amount.satoshis.toLong),
              mintingCommand
            )
          )
        } else
          None
      case (
            WaitingForRedemption,
            _
          ) =>
        None // No transition
      case (
            WaitingForClaim,
            _
          ) =>
        None // No transition
      case (MintingTBTC(_, _), _) =>
        None // No transition
      case (
            WaitingForBTC(_, _),
            _
          ) =>
        None // No transition
    }

  private def fsmStateToSessionState(
      peginStateMachineState: PeginStateMachineState
  ): PeginSessionState = peginStateMachineState match {
    case MintingTBTC(_, amount) => PeginSessionStateMintingTBTC(amount)
    case WaitingForBTC(_, _)    => PeginSessionStateWaitingForBTC
    case WaitingForRedemption   => PeginSessionWaitingForRedemption
    case WaitingForClaim        => PeginSessionStateWaitingForBTC
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
              WaitingForBTC(psi.escrowAddress, psi.redeemAddress)
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
