package co.topl.bridge.consensus.persistence

import co.topl.bridge.consensus.AssetToken
import co.topl.bridge.consensus.BifrostCurrencyUnit
import co.topl.bridge.consensus.GroupToken
import co.topl.bridge.consensus.Lvl
import co.topl.bridge.consensus.SeriesToken
import co.topl.bridge.consensus.protobuf.BifrostCurrencyUnit.Currency.{
  AssetToken => AssetTokenCurrency
}
import co.topl.bridge.consensus.protobuf.BifrostCurrencyUnit.Currency.{
  GroupToken => GroupTokenCurrency
}
import co.topl.bridge.consensus.protobuf.BifrostCurrencyUnit.Currency.{
  Lvl => LvlCurrency
}
import co.topl.bridge.consensus.protobuf.BifrostCurrencyUnit.Currency.{
  SeriesToken => SeriesTokenCurrency
}
import co.topl.bridge.consensus.protobuf.BlockchainEvent.Event.{
  BifrostFundsDeposited => BifrostFundsDepositedEvent
}
import co.topl.bridge.consensus.protobuf.BlockchainEvent.Event.{
  BifrostFundsWithdrawn => BifrostFundsWithdrawnEvent
}
import co.topl.bridge.consensus.protobuf.BlockchainEvent.Event.{
  BtcFundsDeposited => BtcFundsDepositedEvent
}
import co.topl.bridge.consensus.protobuf.BlockchainEvent.Event.{
  BtcFundsWithdrawn => BtcFundsWithdrawnEvent
}
import co.topl.bridge.consensus.protobuf.BlockchainEvent.Event.{
  NewBTCBlock => NewBTCBlockEvent
}
import co.topl.bridge.consensus.protobuf.BlockchainEvent.Event.{
  NewToplBlock => NewToplBlockEvent
}
import co.topl.bridge.consensus.protobuf.BlockchainEvent.Event.{
  SkippedBTCBlock => SkippedBTCBlockEvent
}
import co.topl.bridge.consensus.protobuf.BlockchainEvent.Event.{
  SkippedToplBlock => SkippedToplBlockEvent
}
import co.topl.bridge.consensus.protobuf.{AssetToken => AssetTokenPb}
import co.topl.bridge.consensus.protobuf.{
  BTCFundsDeposited => BTCFundsDepositedPb
}
import co.topl.bridge.consensus.protobuf.{
  BTCFundsWithdrawn => BTCFundsWithdrawnPb
}
import co.topl.bridge.consensus.protobuf.{
  BifrostCurrencyUnit => BifrostCurrencyUnitPb
}
import co.topl.bridge.consensus.protobuf.{
  BifrostFundsDeposited => BifrostFundsDepositedPb
}
import co.topl.bridge.consensus.protobuf.{
  BifrostFundsWithdrawn => BifrostFundsWithdrawnPb
}
import co.topl.bridge.consensus.protobuf.{BlockchainEvent => BlockchainEventPb}
import co.topl.bridge.consensus.protobuf.{GroupToken => GroupTokenPb}
import co.topl.bridge.consensus.protobuf.{Lvl => LvlPb}
import co.topl.bridge.consensus.protobuf.{NewBTCBlock => NewBTCBlockPb}
import co.topl.bridge.consensus.protobuf.{NewToplBlock => NewToplBlockPb}
import co.topl.bridge.consensus.protobuf.{SeriesToken => SeriesTokenPb}
import co.topl.bridge.consensus.protobuf.{SkippedBTCBlock => SkippedBTCBlockPb}
import co.topl.bridge.consensus.protobuf.{
  SkippedToplBlock => SkippedToplBlockPb
}
import com.google.protobuf.ByteString
import co.topl.bridge.consensus.monitor.{BifrostFundsDeposited, BlockchainEvent, BTCFundsWithdrawn, BTCFundsDeposited, BifrostFundsWithdrawn, SkippedToplBlock, SkippedBTCBlock, NewToplBlock, NewBTCBlock}

trait SerializationOps {

  def toProtobuf(amount: BifrostCurrencyUnit) = amount match {
    case Lvl(amount) =>
      Some(
        BifrostCurrencyUnitPb(
          LvlCurrency(
            LvlPb(amount.value)
          )
        )
      )
    case SeriesToken(id, amount) =>
      Some(
        BifrostCurrencyUnitPb(
          SeriesTokenCurrency(
            SeriesTokenPb(id, amount.value)
          )
        )
      )
    case GroupToken(id, amount) =>
      Some(
        BifrostCurrencyUnitPb(
          GroupTokenCurrency(
            GroupTokenPb(id, amount.value)
          )
        )
      )
    case AssetToken(groupId, seriesId, amount) =>
      Some(
        BifrostCurrencyUnitPb(
          AssetTokenCurrency(
            AssetTokenPb(groupId, seriesId, amount.value)
          )
        )
      )
  }

  def toProtobuf(event: BlockchainEvent): BlockchainEventPb =
    event match {
      case NewBTCBlock(height) =>
        BlockchainEventPb(
          NewBTCBlockEvent(NewBTCBlockPb(height))
        )
      case BTCFundsWithdrawn(txId, vout) =>
        BlockchainEventPb(
          BtcFundsWithdrawnEvent(BTCFundsWithdrawnPb(txId, vout))
        )
      case SkippedBTCBlock(height) =>
        BlockchainEventPb(
          SkippedBTCBlockEvent(SkippedBTCBlockPb(height))
        )
      case SkippedToplBlock(height) =>
        BlockchainEventPb(
          SkippedToplBlockEvent(SkippedToplBlockPb(height))
        )
      case NewToplBlock(height) =>
        BlockchainEventPb(
          NewToplBlockEvent(NewToplBlockPb(height))
        )
      case BTCFundsDeposited(
            fundsDepositedHeight,
            scriptPubKey,
            txId,
            vout,
            amount
          ) =>
        BlockchainEventPb(
          BtcFundsDepositedEvent(
            BTCFundsDepositedPb(
              fundsDepositedHeight,
              scriptPubKey,
              txId,
              vout,
              ByteString.copyFrom(amount.satoshis.bytes.toArray)
            )
          )
        )
      case BifrostFundsDeposited(
            currentToplBlockHeight,
            address,
            utxoTxId,
            utxoIndex,
            amount
          ) =>
        BlockchainEventPb(
          BifrostFundsDepositedEvent(
            BifrostFundsDepositedPb(
              currentToplBlockHeight,
              address,
              utxoTxId,
              utxoIndex,
              toProtobuf(amount)
            )
          )
        )
      case BifrostFundsWithdrawn(txId, txIndex, secret, amount) =>
        BlockchainEventPb(
          BifrostFundsWithdrawnEvent(
            BifrostFundsWithdrawnPb(
              txId,
              txIndex,
              secret,
              toProtobuf(amount)
            )
          )
        )
    }
}
