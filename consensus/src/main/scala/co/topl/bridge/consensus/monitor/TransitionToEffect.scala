package co.topl.bridge.consensus.monitor

import cats.effect.kernel.Async
import cats.implicits._
import co.topl.bridge.consensus.BTCConfirmationThreshold
import co.topl.bridge.consensus.BTCRetryThreshold
import co.topl.bridge.consensus.ToplConfirmationThreshold
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.managers.BTCWalletAlgebra
import co.topl.bridge.consensus.monitor.MConfirmingBTCDeposit
import co.topl.bridge.consensus.monitor.MMintingTBTC
import co.topl.bridge.consensus.monitor.MWaitingForBTCDeposit
import co.topl.bridge.consensus.monitor.MintingTBTCConfirmation
import co.topl.bridge.consensus.monitor.PeginStateMachineState
import co.topl.bridge.consensus.monitor.WaitingForClaim
import co.topl.bridge.consensus.monitor.WaitingForRedemption
import co.topl.bridge.consensus.monitor.WaitingForRedemptionOps
import co.topl.bridge.consensus.service.ConfirmDepositBTCOperation
import co.topl.bridge.consensus.service.ConfirmTBTCMintOperation
import co.topl.bridge.consensus.service.PostDepositBTCOperation
import co.topl.bridge.consensus.service.PostRedemptionTxOperation
import co.topl.bridge.consensus.service.PostTBTCMintOperation
import co.topl.bridge.consensus.service.TimeoutDepositBTCOperation
import co.topl.bridge.consensus.service.TimeoutTBTCMintOperation
import co.topl.bridge.consensus.service.UndoDepositBTCOperation
import co.topl.bridge.consensus.service.UndoTBTCMintOperation
import co.topl.shared.ClientId
import co.topl.shared.ConsensusClientGrpc
import co.topl.shared.SessionId
import com.google.protobuf.ByteString
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.core.currency.{CurrencyUnit => BitcoinCurrencyUnit}
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import scodec.bits.ByteVector
import co.topl.bridge.consensus.service.PostClaimTxOperation
import co.topl.bridge.consensus.service.ConfirmClaimTxOperation
import co.topl.bridge.consensus.service.UndoClaimTxOperation

trait TransitionToEffect {

  import WaitingForRedemptionOps._

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
      bitcoindInstance: BitcoindRpcClient,
      pegInWalletManager: BTCWalletAlgebra[F],
      defaultFeePerByte: BitcoinCurrencyUnit,
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcRetryThreshold: BTCRetryThreshold,
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
              consensusClient.postTBTCMint(
                PostTBTCMintOperation(
                  session.id,
                  be.currentToplBlockHeight,
                  be.utxoTxId,
                  be.utxoIndex,
                  ByteString.copyFrom(be.amount.amount.toByteArray)
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
              _: MintingTBTCConfirmation,
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
              cs: MintingTBTCConfirmation,
              be: NewToplBlock
            ) =>
          if ( // FIXME: check that this is the right time to wait
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
          else
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
        case (
              cs: WaitingForRedemption,
              ev: BifrostFundsWithdrawn
            ) =>
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
                  ByteString.copyFrom(ev.amount.amount.toByteArray)
                )
              )
            )
            .void
        case (
              _: WaitingForClaim,
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
              cs: WaitingForClaimBTCConfirmation,
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
              cs: WaitingForClaim,
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
                    startClaimingProcess(
                      cs.secret,
                      cs.claimAddress,
                      cs.currentWalletIdx,
                      cs.btcTxId,
                      cs.btcVout,
                      cs.scriptAsm, // scriptAsm,
                      Satoshis
                        .fromBytes(ByteVector(cs.amount.amount.toByteArray))
                    )
                  )
                  .void
              else
                Async[F].unit
          }
        case (_, _) => Async[F].unit
      })

}
