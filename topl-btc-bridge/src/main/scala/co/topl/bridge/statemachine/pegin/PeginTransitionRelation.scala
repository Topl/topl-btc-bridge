package co.topl.bridge.statemachine.pegin

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.bridge.Fellowship
import co.topl.bridge.Lvl
import co.topl.bridge.Template
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.bridge.managers.TransactionAlgebra
import org.bitcoins.core.currency.{CurrencyUnit => BitcoinCurrencyUnit}
import org.bitcoins.core.protocol.Bech32Address
import quivr.models.KeyPair
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.builders.TransactionBuilderApi
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import co.topl.bridge.managers.BTCWalletAlgebra

object PeginTransitionRelation {

  import WaitingBTCOps._
  import WaitingForRedemptionOps._

  def transitionToEffect[F[_]: Async](
      currentState: PeginStateMachineState,
      blockchainEvent: BlockchainEvent
  )(implicit
      toplKeypair: KeyPair,
      bitcoindInstance: BitcoindRpcClient,
      pegInWalletManager: BTCWalletAlgebra[F],
      walletStateApi: WalletStateAlgebra[F],
      transactionBuilderApi: TransactionBuilderApi[F],
      defaultFromFellowship: Fellowship,
      defaultFromTemplate: Template,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      transactionAlgebra: TransactionAlgebra[F],
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
              redeemAddress,
              _
            ),
            BTCFundsDeposited(_, _, _, amount)
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
            BTCFundsDeposited(scriptPubKey, _, _, _)
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
              currentWalletIdx,
              scriptAsm,
              escrowAddress,
              redeemAddress,
              claimAddress
            ),
            BTCFundsDeposited(scriptPubKey, txId, vout, amount)
          ) =>
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
