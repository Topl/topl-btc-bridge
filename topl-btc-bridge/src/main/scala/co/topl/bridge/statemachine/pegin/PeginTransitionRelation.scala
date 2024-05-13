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

  def handleBlockchainEvent[F[_]: Async](
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
  ): Option[FSMTransition] =
    (currentState, blockchainEvent) match {
      case (
            WaitingForRedemption(
              currentWalletIdx,
              scriptAsm,
              _,
              claimAddress,
              btcTxId,
              btcVout,
              utxoTxId,
              utxoIndex
            ),
            BifrostFundsWithdrawn(txId, txIndex, secret, amount)
          ) =>
        if (utxoTxId == txId && utxoIndex == txIndex) {
          import co.topl.brambl.syntax._
          val claimingCommand =
            Async[F]
              .start(
                startClaimingProcess(
                  secret,
                  claimAddress,
                  currentWalletIdx,
                  btcTxId,
                  btcVout,
                  scriptAsm, // scriptAsm,
                  amount.amount.toLong, // amount,
                  defaultFeePerByte
                )
              )
              .void
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForClaim(claimAddress),
              claimingCommand
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
            MintingTBTC(
              currentWalletIdx,
              scriptAsm,
              redeemAddress,
              claimAddress,
              btcTxId,
              btcVout,
              _
            ),
            BifrostFundsDeposited(address, utxoTxId, utxoIndex, _)
          ) =>
        if (redeemAddress == address) {
          Some(
            FSMTransitionTo(
              currentState,
              WaitingForRedemption(
                currentWalletIdx,
                scriptAsm,
                redeemAddress,
                claimAddress,
                btcTxId,
                btcVout,
                utxoTxId,
                utxoIndex
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
          val mintingCommand = Async[F]
            .start(
              startMintingProcess[F](
                defaultFromFellowship,
                defaultFromTemplate,
                redeemAddress,
                toplKeypair,
                amount.satoshis.toLong,
                toplWalletAlgebra,
                transactionAlgebra,
                utxoAlgebra,
                defaultMintingFee
              )
            )
            .void
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
              mintingCommand
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
