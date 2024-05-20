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
import co.topl.bridge.PeginSessionState.PeginSessionWaitingForClaim
import co.topl.bridge.Template
import co.topl.bridge.managers.PeginSessionInfo
import co.topl.bridge.managers.PegoutSessionInfo
import co.topl.bridge.managers.SessionCreated
import co.topl.bridge.managers.SessionEvent
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.managers.SessionUpdated
import org.bitcoins.core.currency.{CurrencyUnit => BitcoinCurrencyUnit}
import org.typelevel.log4cats.Logger
import quivr.models.KeyPair

import java.util.Map.Entry
import java.util.concurrent.ConcurrentHashMap
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.WalletStateAlgebra
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import co.topl.bridge.managers.BTCWalletAlgebra
import co.topl.brambl.wallet.WalletApi
import cats.effect.kernel.Resource
import io.grpc.ManagedChannel
import cats.effect.kernel.Ref
import co.topl.bridge.BTCWaitExpirationTime

trait PeginStateMachineAlgebra[F[_]] {

  def handleBlockchainEventInContext(
      blockchainEvent: BlockchainEvent
  ): fs2.Stream[F, F[Unit]]

  def innerStateConfigurer(
      sessionEvent: SessionEvent
  ): F[Unit]

}

object PeginStateMachine {

  def make[F[_]: Async: Logger](
      currentBitcoinNetworkHeight: Ref[F, Int],
      map: ConcurrentHashMap[String, PeginStateMachineState]
  )(implicit
      sessionManager: SessionManagerAlgebra[F],
      walletApi: WalletApi[F],
      bitcoindInstance: BitcoindRpcClient,
      pegInWalletManager: BTCWalletAlgebra[F],
      toplKeypair: KeyPair,
      walletStateApi: WalletStateAlgebra[F],
      transactionBuilderApi: TransactionBuilderApi[F],
      utxoAlgebra: GenusQueryAlgebra[F],
      defaultFromFellowship: Fellowship,
      defaultFromTemplate: Template,
      defaultFeePerByte: BitcoinCurrencyUnit,
      defaultMintingFee: Lvl,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      channelResource: Resource[F, ManagedChannel]
  ) = new PeginStateMachineAlgebra[F] {

    import org.typelevel.log4cats.syntax._
    import PeginTransitionRelation._

    private def updateBTCHeight(
        blockchainEvent: BlockchainEvent
    ): fs2.Stream[F, F[Unit]] =
      blockchainEvent match {
        case NewBTCBlock(height) =>
          fs2.Stream(
            for {
              x <- currentBitcoinNetworkHeight.get
              _ <-
                if (height > x) // TODO: handle reorgs
                  currentBitcoinNetworkHeight.set(height)
                else Sync[F].unit
            } yield ()
          )

        case _ => fs2.Stream.empty
      }

    def handleBlockchainEventInContext(
        blockchainEvent: BlockchainEvent
    ): fs2.Stream[F, F[Unit]] = {
      import scala.jdk.CollectionConverters._
      updateBTCHeight(blockchainEvent) ++ (for {
        entry <- fs2.Stream[F, Entry[String, PeginStateMachineState]](
          map.entrySet().asScala.toList: _*
        )
        sessionId = entry.getKey
        currentState = entry.getValue
      } yield handleBlockchainEvent[F](
        currentState,
        blockchainEvent
      )(transitionToEffect[F])
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
      case _: WaitingForClaim      => PeginSessionWaitingForClaim
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
            currentBitcoinNetworkHeight.get.flatMap(cHeight =>
              Sync[F].delay(
                map.put(
                  sessionId,
                  WaitingForBTC(
                    cHeight,
                    psi.btcPeginCurrentWalletIdx,
                    psi.scriptAsm,
                    psi.escrowAddress,
                    psi.redeemAddress,
                    psi.claimAddress
                  )
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
}
