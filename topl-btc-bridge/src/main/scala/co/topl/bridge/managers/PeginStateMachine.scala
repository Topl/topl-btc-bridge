package co.topl.bridge.managers

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.bridge.PeginSessionState.PeginSessionStateMintingTBTC
import co.topl.bridge.PeginSessionState.PeginSessionStateWaitingForBTC
import co.topl.bridge.PeginSessionState.PeginSessionWaitingForRedemption
import org.bitcoins.core.protocol.blockchain.Block
import quivr.models.KeyPair

import cats.implicits._
import java.util.concurrent.ConcurrentHashMap
import org.typelevel.log4cats.Logger

sealed trait PeginStateMachineState

case class WaitingForBTC(escrowAddress: String) extends PeginStateMachineState
case class MintingTBTC(amount: Long) extends PeginStateMachineState
case object WaitingForRedemption extends PeginStateMachineState

class PeginStateMachine[F[_]: Async: Logger](
    waitingBTCForBlock: WaitingBTCForBlock[F],
    toplKeypair: KeyPair,
    fromFellowship: String,
    fromTemplate: String,
    toplWalletAlgebra: ToplWalletAlgebra[F],
    transactionAlgebra: TransactionAlgebra[F],
    utxoAlgebra: GenusQueryAlgebra[F],
    map: ConcurrentHashMap[String, PeginStateMachineState]
) {
  import org.typelevel.log4cats.syntax._

  def handleNewBlock(block: Block) = {
    import scala.jdk.CollectionConverters._
    info"Starting handling new block ${block.blockHeader.hashBE.hex}" >> (for {
      entry <- map.entrySet().asScala.toList
      transaction <- block.transactions.toList
      output <- transaction.outputs.groupBy(_.scriptPubKey)
      sessionId = entry.getKey
      currentState = entry.getValue
    } yield {
      currentState match {
        case WaitingForBTC(escrowAddress) =>
          waitingBTCForBlock.handleWaitingBTCForBlock(
            sessionId,
            toplKeypair,
            fromFellowship,
            fromTemplate,
            escrowAddress,
            toplWalletAlgebra,
            transactionAlgebra,
            utxoAlgebra,
            output
          )
        case MintingTBTC(_) =>
          Async[F].unit
        case WaitingForRedemption =>
          Async[F].unit
      }
    }).sequence.void

  }

  def innerStateConfigurer(
      sessionEvent: SessionEvent
  ): F[Unit] = {
    sessionEvent match {
      case SessionCreated(
            sessionId,
            PeginSessionInfo(_, _, redeemAddress, _, _, _, _)
          ) =>
        info"New session created, waiting for funds at $redeemAddress" >> Sync[
          F
        ].delay(
          map.put(
            sessionId,
            WaitingForBTC(redeemAddress)
          )
        )
      case SessionUpdated(
            sessionId,
            PeginSessionInfo(_, _, _, _, _, _, state)
          ) =>
        val newState = state match {
          case PeginSessionStateMintingTBTC(amount) =>
            MintingTBTC(amount)
          case PeginSessionWaitingForRedemption =>
            WaitingForRedemption
          case PeginSessionStateWaitingForBTC =>
            throw new Exception("Invalid state.") // This should not happens
        }
        Sync[F].delay(
          map.replace(
            sessionId,
            newState
          )
        )
      case SessionCreated(_, _: PegoutSessionInfo) =>
        ??? // TODO: complete for pegout
      case SessionUpdated(_, _: PegoutSessionInfo) =>
        ??? // TODO: complete for pegout
    }
  }

}
