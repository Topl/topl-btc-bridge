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

object PeginTransitionRelation {

  import WaitingBTCOps._
  import WaitingForRedemptionOps._

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
      defaultMintingFee: Lvl
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
            WaitingForBTC(
              _,
              _,
              _,
              _,
              redeemAddress,
              _
            ),
            BTCFundsDeposited(_, _, _, _, amount)
          ) =>
        Async[F]
          .start(
            startMintingProcess[F](
              defaultFromFellowship,
              defaultFromTemplate,
              redeemAddress,
              amount.satoshis.toLong
            )
          )
          .void
      case (_, _) => Async[F].unit
    }

  def handleBlockchainEvent[F[_]: Async](
      currentState: PeginStateMachineState,
      blockchainEvent: BlockchainEvent
  )(
      t2E: (PeginStateMachineState, BlockchainEvent) => F[Unit]
  )(implicit
      btcWaitExpirationTime: BTCWaitExpirationTime
  ): Option[FSMTransition] =
    (currentState, blockchainEvent) match {
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
            BTCFundsDeposited(_, scriptPubKey, _, _, _)
          ) =>
        val bech32Address = Bech32Address.fromString(claimAddress)
        if (scriptPubKey == bech32Address.scriptPubKey) {
          Some(
            EndTrasition[F](
              Async[F].unit
            )
          )
        } else None
      case (
            cs: MintingTBTC,
            be: BifrostFundsDeposited
          ) =>
        if (cs.redeemAddress == be.address) {
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForRedemption(
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
            WaitingForBTC(
              startBlockHeight,
              currentWalletIdx,
              scriptAsm,
              escrowAddress,
              redeemAddress,
              claimAddress
            ),
            BTCFundsDeposited(
              currentBTCBlockHeight,
              scriptPubKey,
              txId,
              vout,
              amount
            )
          ) =>
        if (
          currentBTCBlockHeight - startBlockHeight > btcWaitExpirationTime.underlying
        )
          Some(
            EndTrasition[F](
              Async[F].unit
            )
          )
        else {
          val bech32Address = Bech32Address.fromString(escrowAddress)
          if (scriptPubKey == bech32Address.scriptPubKey) {
            Some(
              FSMTransitionTo(
                currentState,
                MintingTBTC(
                  currentWalletIdx,
                  scriptAsm,
                  redeemAddress,
                  claimAddress,
                  txId,
                  vout,
                  amount.satoshis.toLong
                ),
                t2E(currentState, blockchainEvent)
              )
            )
          } else
            None
        }
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
      case (
            _: WaitingForBTC,
            _
          ) =>
        None // No transition
    }
}
