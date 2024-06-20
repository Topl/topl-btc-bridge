package co.topl.bridge.statemachine.pegin

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.BTCWaitExpirationTime
import co.topl.bridge.Fellowship
import co.topl.bridge.Lvl
import co.topl.bridge.Template
import co.topl.bridge.managers.BTCWalletAlgebra
import io.grpc.ManagedChannel
import org.bitcoins.core.currency.{CurrencyUnit => BitcoinCurrencyUnit}
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import quivr.models.KeyPair
import co.topl.bridge.ToplWaitExpirationTime
import co.topl.bridge.BTCConfirmationThreshold
import co.topl.brambl.models.SeriesId
import co.topl.brambl.models.GroupId
import co.topl.bridge.AssetToken
import co.topl.brambl.utils.Encoding

object PeginTransitionRelation {

  import WaitingBTCOps._
  import WaitingForRedemptionOps._

  private def isAboveThreshold(
      currentHeight: Int,
      startHeight: Int
  )(implicit btcConfirmationThreshold: BTCConfirmationThreshold) =
    currentHeight - startHeight > btcConfirmationThreshold.underlying

  def transitionToEffect[F[_]: Async](
      currentState: PeginStateMachineState,
      blockchainEvent: BlockchainEvent
  )(implicit
      toplKeypair: KeyPair,
      walletApi: WalletApi[F],
      bitcoindInstance: BitcoindRpcClient,
      pegInWalletManager: BTCWalletAlgebra[F],
      walletStateApi: WalletStateAlgebra[F],
      transactionBuilderApi: TransactionBuilderApi[F],
      channelResource: Resource[F, ManagedChannel],
      defaultFromFellowship: Fellowship,
      defaultFromTemplate: Template,
      utxoAlgebra: GenusQueryAlgebra[F],
      defaultFeePerByte: BitcoinCurrencyUnit,
      defaultMintingFee: Lvl,
      btcConfirmationThreshold: BTCConfirmationThreshold
  ) =
    (currentState, blockchainEvent) match {
      case (
            cs: WaitingForRedemption,
            BifrostFundsWithdrawn(_, _, secret, amount)
          ) =>
        import co.topl.brambl.syntax._
        Async[F]
          .start(
            startClaimingProcess(
              secret,
              cs.claimAddress,
              cs.currentWalletIdx,
              cs.btcTxId,
              cs.btcVout,
              cs.scriptAsm, // scriptAsm,
              amount.amount.toLong // amount,
            )
          )
          .void
      case (
            cs: WaitingForEscrowBTCConfirmation,
            newBTCBLock: NewBTCBlock
          ) =>
        if (isAboveThreshold(newBTCBLock.height, cs.depositBTCBlockHeight))
          Async[F]
            .start(
              startMintingProcess[F](
                defaultFromFellowship,
                defaultFromTemplate,
                cs.redeemAddress,
                cs.amount
              )
            )
            .void
        else Async[F].unit

      case (_, _) => Async[F].unit
    }

  def handleBlockchainEvent[F[_]: Async](
      currentState: PeginStateMachineState,
      blockchainEvent: BlockchainEvent
  )(
      t2E: (PeginStateMachineState, BlockchainEvent) => F[Unit]
  )(implicit
      btcWaitExpirationTime: BTCWaitExpirationTime,
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcConfirmationThreshold: BTCConfirmationThreshold,
      groupId: GroupId,
      seriesId: SeriesId
  ): Option[FSMTransition] =
    (currentState, blockchainEvent) match {
      case (
            cs: WaitingForRedemption,
            ev: NewToplBlock
          ) =>
        if (
          toplWaitExpirationTime.underlying < (ev.height - cs.currentTolpBlockHeight)
        )
          Some(
            EndTransition[F](
              Async[F].delay(
                println(
                  "cs.currentTolpBlockHeight: " + cs.currentTolpBlockHeight
                )
              ) >>
                Async[F].delay(println("ev.height: " + ev.height)) >>
                Async[F].delay(
                  println(
                    "toplWaitExpirationTime: " + toplWaitExpirationTime.underlying
                  )
                ) >>
                Async[F].unit
            )
          )
        else
          None
      case (
            cs: WaitingForEscrowBTCConfirmation,
            ev: NewBTCBlock
          ) =>
        // check that the confirmation threshold has been passed
        if (isAboveThreshold(ev.height, cs.depositBTCBlockHeight))
          Some(
            FSMTransitionTo(
              currentState,
              MintingTBTC(
                ev.height,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                cs.amount
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        else if (ev.height <= cs.depositBTCBlockHeight)
          // we are seeing the block where the transaction was found again
          // this can only mean that block is being unapplied
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForBTC(
                cs.startBTCBlockHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.escrowAddress,
                cs.redeemAddress,
                cs.claimAddress
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        else
          None
      case (
            cs: WaitingForClaimBTCConfirmation,
            ev: NewBTCBlock
          ) =>
        // check that the confirmation threshold has been passed
        if (isAboveThreshold(ev.height, cs.claimBTCBlockHeight))
          Some(
            EndTransition[F](
              Async[F].unit
            )
          )
        else if (ev.height <= cs.claimBTCBlockHeight)
          // we are seeing the block where the transaction was found again
          // this can only mean that block is being unapplied
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForClaim(
                cs.claimAddress
              ),
              t2E(currentState, blockchainEvent)
            )
          )
        else
          None
      case (
            cs: WaitingForRedemption,
            be: BifrostFundsWithdrawn
          ) =>
        if (cs.utxoTxId == be.txId && cs.utxoIndex == be.txIndex) {
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForClaim(cs.claimAddress),
              t2E(currentState, blockchainEvent)
            )
          )
        } else None
      case (
            WaitingForClaim(claimAddress),
            BTCFundsDeposited(depositBTCBlockHeight, scriptPubKey, _, _, _)
          ) =>
        val bech32Address = Bech32Address.fromString(claimAddress)
        if (scriptPubKey == bech32Address.scriptPubKey) {
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForClaimBTCConfirmation(
                depositBTCBlockHeight,
                claimAddress
              ),
              t2E(
                currentState,
                blockchainEvent
              )
            )
          )
        } else None
      case (
            cs: WaitingForBTC,
            ev: NewBTCBlock
          ) =>
        if (
          btcWaitExpirationTime.underlying < (ev.height - cs.currentBTCBlockHeight)
        )
          Some(
            EndTransition[F](
              Async[F].unit
            )
          )
        else
          None
      case (
            cs: MintingTBTC,
            ev: NewBTCBlock
          ) =>
        if (
          ev.height - cs.startBTCBlockHeight > btcWaitExpirationTime.underlying
        )
          Some(
            EndTransition[F](
              Async[F].unit
            )
          )
        else
          None
      case (
            cs: MintingTBTC,
            be: BifrostFundsDeposited
          ) =>
        import co.topl.brambl.syntax._

        if (
          cs.redeemAddress == be.address &&
          AssetToken(
            Encoding.encodeToBase58(groupId.value.toByteArray),
            Encoding.encodeToBase58(seriesId.value.toByteArray),
            cs.amount
          ) == be.amount
        ) {
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForRedemption(
                be.currentToplBlockHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.redeemAddress,
                cs.claimAddress,
                cs.btcTxId,
                cs.btcVout,
                be.utxoTxId,
                be.utxoIndex
              ),
              Sync[F].unit
            )
          )
        } else None
      case (
            cs: WaitingForBTC,
            ev: BTCFundsDeposited
          ) =>
        val bech32Address = Bech32Address.fromString(cs.escrowAddress)
        if (ev.scriptPubKey == bech32Address.scriptPubKey) {
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForEscrowBTCConfirmation(
                cs.currentBTCBlockHeight,
                ev.fundsDepositedHeight,
                cs.currentWalletIdx,
                cs.scriptAsm,
                cs.escrowAddress,
                cs.redeemAddress,
                cs.claimAddress,
                ev.txId,
                ev.vout,
                ev.amount.satoshis.toLong
              ),
              t2E(currentState, blockchainEvent)
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
            _: WaitingForClaim,
            _
          ) =>
        None // No transition
      case (_: MintingTBTC, _) =>
        None // No transition
      case (_: WaitingForEscrowBTCConfirmation, _) =>
        None // No transition
      case (_: WaitingForClaimBTCConfirmation, _) =>
        None // No transition
      case (
            _: WaitingForBTC,
            _
          ) =>
        None // No transition
    }
}
