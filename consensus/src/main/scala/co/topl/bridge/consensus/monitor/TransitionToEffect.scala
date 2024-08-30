package co.topl.bridge.consensus.monitor

import cats.effect.kernel.Async
import cats.implicits._
import co.topl.bridge.consensus.BTCConfirmationThreshold
import co.topl.bridge.consensus.BTCRetryThreshold
import co.topl.bridge.consensus.ToplConfirmationThreshold
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.monitor.MConfirmingBTCDeposit
import co.topl.bridge.consensus.monitor.MMintingTBTC
import co.topl.bridge.consensus.monitor.MWaitingForBTCDeposit
import co.topl.bridge.consensus.monitor.MConfirmingTBTCMint
import co.topl.bridge.consensus.monitor.PeginStateMachineState
import co.topl.bridge.consensus.monitor.MWaitingForClaim
import co.topl.bridge.consensus.monitor.MWaitingForRedemption
import co.topl.bridge.shared.ConfirmClaimTxOperation
import co.topl.bridge.shared.ConfirmDepositBTCOperation
import co.topl.bridge.shared.ConfirmTBTCMintOperation
import co.topl.bridge.shared.PostClaimTxOperation
import co.topl.bridge.shared.PostDepositBTCOperation
import co.topl.bridge.shared.PostRedemptionTxOperation
import co.topl.bridge.shared.PostTBTCMintOperation
import co.topl.bridge.shared.TimeoutDepositBTCOperation
import co.topl.bridge.shared.TimeoutTBTCMintOperation
import co.topl.bridge.shared.UndoClaimTxOperation
import co.topl.bridge.shared.UndoDepositBTCOperation
import co.topl.bridge.shared.UndoTBTCMintOperation
import co.topl.shared.ClientId
import co.topl.shared.ConsensusClientGrpc
import co.topl.shared.SessionId
import com.google.protobuf.ByteString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

trait TransitionToEffect {


  def isAboveConfirmationThresholdBTC(
      currentHeight: Int,
      startHeight: Int
  )(implicit btcConfirmationThreshold: BTCConfirmationThreshold) =
    currentHeight - startHeight > btcConfirmationThreshold.underlying

  def isAboveConfirmationThresholdTopl(
      currentHeight: Long,
      startHeight: Long
  )(implicit toplConfirmationThreshold: ToplConfirmationThreshold) =
    currentHeight - startHeight > toplConfirmationThreshold.underlying

  def transitionToEffect[F[_]: Async: Logger](
      currentState: PeginStateMachineState,
      blockchainEvent: BlockchainEvent
  )(implicit
      clientId: ClientId,
      session: SessionId,
      consensusClient: ConsensusClientGrpc[F],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcRetryThreshold: BTCRetryThreshold,
      toplConfirmationThreshold: ToplConfirmationThreshold,
      btcConfirmationThreshold: BTCConfirmationThreshold
  ) =
    (blockchainEvent match {
      case SkippedToplBlock(height) =>
        error"Error the processor skipped Topl block $height"
      case SkippedBTCBlock(height) =>
        error"Error the processor skipped BTC block $height"
      case NewToplBlock(height) =>
        debug"New Topl block $height"
      case NewBTCBlock(height) =>
        debug"New BTC block $height"
      case _ =>
        Async[F].unit
    }) >>
      ((currentState, blockchainEvent) match {
        case (
              _: MWaitingForBTCDeposit,
              ev: BTCFundsDeposited
            ) =>
          Async[F]
            .start(
              consensusClient.postDepositBTC(
                PostDepositBTCOperation(
                  session.id,
                  ev.fundsDepositedHeight,
                  ev.txId,
                  ev.vout,
                  ByteString.copyFrom(ev.amount.satoshis.toBigInt.toByteArray)
                )
              )
            )
            .void
        case (
              _: MWaitingForBTCDeposit,
              ev: NewBTCBlock
            ) =>
          Async[F]
            .start(
              consensusClient.timeoutDepositBTC(
                TimeoutDepositBTCOperation(
                  session.id,
                  ev.height
                )
              )
            )
            .void
        case (
              cs: MConfirmingBTCDeposit,
              ev: NewBTCBlock
            ) =>
          if (
            isAboveConfirmationThresholdBTC(ev.height, cs.depositBTCBlockHeight)
          ) {
            Async[F]
              .start(
                consensusClient.confirmDepositBTC(
                  ConfirmDepositBTCOperation(
                    session.id,
                    ByteString
                      .copyFrom(cs.amount.satoshis.toBigInt.toByteArray),
                    ev.height
                  )
                )
              )
              .void
          } else {
            Async[F]
              .start(
                consensusClient.undoDepositBTC(
                  UndoDepositBTCOperation(
                    session.id
                  )
                )
              )
              .void
          }
        case (
              _: MMintingTBTC,
              be: BifrostFundsDeposited
            ) =>
          Async[F]
            .start(
              consensusClient
                .postTBTCMint(
                  PostTBTCMintOperation(
                    session.id,
                    be.currentToplBlockHeight,
                    be.utxoTxId,
                    be.utxoIndex,
                    ByteString.copyFrom(be.amount.amount.value.toByteArray)
                  )
                )
            )
            .void
        case (
              _: MMintingTBTC,
              ev: NewBTCBlock
            ) =>
          Async[F]
            .start(
              consensusClient.timeoutTBTCMint(
                TimeoutTBTCMintOperation(
                  session.id,
                  ev.height
                )
              )
            )
            .void
        case ( // TODO: make sure that by the time we are here, the funds are already locked
              _: MConfirmingTBTCMint,
              _: NewBTCBlock
            ) =>
          Async[F]
            .start(
              consensusClient.undoTBTCMint(
                UndoTBTCMintOperation(
                  session.id
                )
              )
            )
            .void
        case (
              cs: MConfirmingTBTCMint,
              be: NewToplBlock
            ) =>
          if (
            isAboveConfirmationThresholdTopl(
              be.height,
              cs.depositTBTCBlockHeight
            )
          ) {
            Async[F]
              .start(
                consensusClient.confirmTBTCMint(
                  ConfirmTBTCMintOperation(
                    session.id,
                    be.height
                  )
                )
              )
              .void
          } else if ( // FIXME: check that this is the right time to wait
            toplWaitExpirationTime.underlying < (be.height - cs.depositTBTCBlockHeight)
          )
            Async[F]
              .start(
                consensusClient.timeoutTBTCMint(
                  TimeoutTBTCMintOperation(
                    session.id
                  )
                )
              )
              .void
          else if (be.height <= cs.depositTBTCBlockHeight)
            Async[F]
              .start(
                consensusClient.undoTBTCMint(
                  UndoTBTCMintOperation(
                    session.id
                  )
                )
              )
              .void
          else
            Async[F].unit
        case (
              cs: MWaitingForRedemption,
              ev: BifrostFundsWithdrawn
            ) =>
          import co.topl.brambl.syntax._
          Async[F]
            .start(
              consensusClient.postRedemptionTx(
                  PostRedemptionTxOperation(
                    session.id,
                    ev.secret,
                    ev.fundsWithdrawnHeight,
                    cs.utxoTxId,
                    cs.utxoIndex,
                    cs.btcTxId,
                    cs.btcVout,
                    ByteString
                      .copyFrom(int128AsBigInt(ev.amount.amount).toByteArray)
                  )
                )
            )
            .void
        case (
              _: MWaitingForRedemption,
              ev: NewToplBlock
            ) =>
          Async[F]
            .start(
              Async[F]
                .start(
                  consensusClient.timeoutTBTCMint(
                    TimeoutTBTCMintOperation(
                      session.id,
                      0,
                      ev.height
                    )
                  )
                )
                .void
            )
            .void
        case (
              _: MWaitingForClaim,
              ev: BTCFundsDeposited
            ) =>
          Async[F]
            .start(
              consensusClient.postClaimTx(
                PostClaimTxOperation(
                  session.id,
                  ev.fundsDepositedHeight,
                  ev.txId,
                  ev.vout
                )
              )
            )
            .void
        case (
              cs: MConfirmingBTCClaim,
              ev: NewBTCBlock
            ) =>
          if (
            isAboveConfirmationThresholdBTC(ev.height, cs.claimBTCBlockHeight)
          )
            Async[F]
              .start(
                  consensusClient.confirmClaimTx(
                    ConfirmClaimTxOperation(
                      session.id,
                      ev.height
                    )
                  )
              )
              .void
          else
            Async[F]
              .start(
                consensusClient.undoClaimTx(
                  UndoClaimTxOperation(
                    session.id
                  )
                )
              )
              .void
        case (
              cs: MWaitingForClaim,
              ev: NewBTCBlock
            ) =>
          // if we the someStartBtcBlockHeight is empty, we need to set it
          // if it is not empty, we need to check if the number of blocks since waiting is bigger than the threshold
          cs.someStartBtcBlockHeight match {
            case None =>
              Async[F].unit
            case Some(startBtcBlockHeight) =>
              if (
                btcRetryThreshold.underlying < (ev.height - startBtcBlockHeight)
              )
                Async[F]
                  .start(
                    warn"Confirming claim tx" >>
                      consensusClient.confirmClaimTx(
                        ConfirmClaimTxOperation(
                          session.id,
                          ev.height
                        )
                      )
                  )
                  .void
              else
                Async[F].unit
          }
        case (_, _) => Async[F].unit
      })

}
