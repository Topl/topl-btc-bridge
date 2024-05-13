package co.topl.bridge.statemachine.pegin

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.bridge.Fellowship
import co.topl.bridge.Lvl
import co.topl.bridge.PeginSessionState
import co.topl.bridge.PeginSessionState.PeginSessionStateMintingTBTC
import co.topl.bridge.PeginSessionState.PeginSessionStateWaitingForBTC
import co.topl.bridge.PeginSessionState.PeginSessionWaitingForRedemption
import co.topl.bridge.Template
import co.topl.bridge.managers.PeginSessionInfo
import co.topl.bridge.managers.PegoutSessionInfo
import co.topl.bridge.managers.SessionCreated
import co.topl.bridge.managers.SessionEvent
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.managers.SessionUpdated
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.bridge.managers.TransactionAlgebra
import org.bitcoins.core.currency.{CurrencyUnit => BitcoinCurrencyUnit}
import org.typelevel.log4cats.Logger
import quivr.models.KeyPair

import java.util.Map.Entry
import java.util.concurrent.ConcurrentHashMap

class PeginStateMachine[F[_]: Async: Logger](
    map: ConcurrentHashMap[String, PeginStateMachineState]
)(implicit
    sessionManager: SessionManagerAlgebra[F],
    toplKeypair: KeyPair,
    toplWalletAlgebra: ToplWalletAlgebra[F],
    transactionAlgebra: TransactionAlgebra[F],
    waitingBTCForBlock: WaitingBTCOps[F],
    waitingForRedemptionOps: WaitingForRedemptionOps[F],
    utxoAlgebra: GenusQueryAlgebra[F],
    defaultFromFellowship: Fellowship,
    defaultFromTemplate: Template,
    defaultFeePerByte: BitcoinCurrencyUnit,
    defaultMintingFee: Lvl
) {
  import org.typelevel.log4cats.syntax._
  import PeginTransitionRelation._

  def handleBlockchainEventInContext(blockchainEvent: BlockchainEvent) = {
    import scala.jdk.CollectionConverters._
    (for {
      entry <- fs2.Stream[F, Entry[String, PeginStateMachineState]](
        map.entrySet().asScala.toList: _*
      )
      sessionId = entry.getKey
      currentState = entry.getValue
    } yield handleBlockchainEvent(
      currentState,
      blockchainEvent
    )
      .map(x =>
        x match {
          case EndTrasition(effect) =>
            info"Session $sessionId ended successfully" >>
              Sync[F].delay(map.remove(sessionId)) >>
              sessionManager
                .removeSession(sessionId)
                .flatMap(_ => effect.asInstanceOf[F[Unit]])
          case FSMTransitionTo(prevState, nextState, effect) =>
            info"Transitioning session $sessionId from ${currentState
                .getClass()
                .getSimpleName()} to ${nextState.getClass().getSimpleName()}" >>
              processTransition(
                sessionId,
                FSMTransitionTo[F](
                  prevState,
                  nextState,
                  effect.asInstanceOf[F[Unit]]
                )
              )
        }
      )).collect({ case Some(value) =>
      info"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()}" >> value
    })

  }

  private def fsmStateToSessionState(
      peginStateMachineState: PeginStateMachineState
  ): PeginSessionState = peginStateMachineState match {
    case _: MintingTBTC          => PeginSessionStateMintingTBTC
    case _: WaitingForBTC        => PeginSessionStateWaitingForBTC
    case _: WaitingForRedemption => PeginSessionWaitingForRedemption
    case _: WaitingForClaim      => PeginSessionStateWaitingForBTC
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
                psi.btcPeginCurrentWalletIdx,
                psi.scriptAsm,
                psi.escrowAddress,
                psi.redeemAddress,
                psi.claimAddress
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
