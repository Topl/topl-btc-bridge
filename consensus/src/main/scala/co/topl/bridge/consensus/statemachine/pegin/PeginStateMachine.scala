package co.topl.bridge.consensus.statemachine.pegin

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.BTCConfirmationThreshold
import co.topl.bridge.consensus.BTCRetryThreshold
import co.topl.bridge.consensus.BTCWaitExpirationTime
import co.topl.bridge.consensus.Fellowship
import co.topl.bridge.consensus.Lvl
import co.topl.bridge.consensus.PeginSessionState
import co.topl.bridge.consensus.PeginSessionState.PeginSessionMintingTBTCConfirmation
import co.topl.bridge.consensus.PeginSessionState.PeginSessionStateMintingTBTC
import co.topl.bridge.consensus.PeginSessionState.PeginSessionStateWaitingForBTC
import co.topl.bridge.consensus.PeginSessionState.PeginSessionWaitingForClaim
import co.topl.bridge.consensus.PeginSessionState.PeginSessionWaitingForClaimBTCConfirmation
import co.topl.bridge.consensus.PeginSessionState.PeginSessionWaitingForEscrowBTCConfirmation
import co.topl.bridge.consensus.PeginSessionState.PeginSessionWaitingForRedemption
import co.topl.bridge.consensus.Template
import co.topl.bridge.consensus.ToplConfirmationThreshold
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.managers.BTCWalletAlgebra
import co.topl.bridge.consensus.managers.PeginSessionInfo
import co.topl.bridge.consensus.managers.PegoutSessionInfo
import co.topl.bridge.consensus.managers.SessionCreated
import co.topl.bridge.consensus.managers.SessionEvent
import co.topl.bridge.consensus.managers.SessionManagerAlgebra
import co.topl.bridge.consensus.managers.SessionUpdated
import co.topl.bridge.consensus.persistence._
import io.grpc.ManagedChannel
import org.bitcoins.core.currency.{CurrencyUnit => BitcoinCurrencyUnit}
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger
import quivr.models.KeyPair

import java.util.Map.Entry
import java.util.concurrent.ConcurrentHashMap

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
      currentToplNetworkHeight: Ref[F, Long],
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
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcRetryThreshold: BTCRetryThreshold,
      btcConfirmationThreshold: BTCConfirmationThreshold,
      toplConfirmationThreshold: ToplConfirmationThreshold,
      channelResource: Resource[F, ManagedChannel],
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId
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

    private def updateToplHeight(
        blockchainEvent: BlockchainEvent
    ): fs2.Stream[F, F[Unit]] =
      blockchainEvent match {
        case NewToplBlock(height) =>
          fs2.Stream(
            for {
              x <- currentToplNetworkHeight.get
              _ <- trace"current topl height is $x"
              _ <- trace"Updating topl height to $height"
              _ <-
                if (height > x)
                  currentToplNetworkHeight.set(height)
                else Sync[F].unit
            } yield ()
          )

        case _ => fs2.Stream.empty
      }

    def handleBlockchainEventInContext(
        blockchainEvent: BlockchainEvent
    ): fs2.Stream[F, F[Unit]] = {
      import scala.jdk.CollectionConverters._
      updateToplHeight(blockchainEvent) ++
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
          .orElse(
            Some(
              FSMTransitionTo(
                currentState,
                currentState,
                transitionToEffect(currentState, blockchainEvent)
              )
            )
          )
          .map(x =>
            x match {
              case EndTransition(effect) =>
                info"Session $sessionId ended successfully" >>
                  Sync[F].delay(map.remove(sessionId)) >>
                  sessionManager
                    .removeSession(sessionId)
                    .flatMap(_ => effect.asInstanceOf[F[Unit]])
              case FSMTransitionTo(prevState, nextState, effect)
                  if (prevState != nextState) =>
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
              case FSMTransitionTo(_, _, _) =>
                Sync[F].unit
            }
          )).collect { case Some(value) =>
          if (blockchainEvent.isInstanceOf[NewBTCBlock])
            debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[NewBTCBlock].height}" >> value
          else if (blockchainEvent.isInstanceOf[SkippedBTCBlock])
            debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[SkippedBTCBlock].height}" >> value
          else if (blockchainEvent.isInstanceOf[NewToplBlock])
            debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()} at height ${blockchainEvent.asInstanceOf[NewToplBlock].height}" >> value
          else
            debug"Processed blockchain event ${blockchainEvent.getClass().getSimpleName()}" >> value
        }
    }

    private def fsmStateToSessionState(
        peginStateMachineState: PeginStateMachineState
    ): PeginSessionState = peginStateMachineState match {
      case _: MintingTBTC             => PeginSessionStateMintingTBTC
      case _: WaitingForBTC           => PeginSessionStateWaitingForBTC
      case _: WaitingForRedemption    => PeginSessionWaitingForRedemption
      case _: WaitingForClaim         => PeginSessionWaitingForClaim
      case _: MintingTBTCConfirmation => PeginSessionMintingTBTCConfirmation
      case _: WaitingForEscrowBTCConfirmation =>
        PeginSessionWaitingForEscrowBTCConfirmation
      case _: WaitingForClaimBTCConfirmation =>
        PeginSessionWaitingForClaimBTCConfirmation
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
