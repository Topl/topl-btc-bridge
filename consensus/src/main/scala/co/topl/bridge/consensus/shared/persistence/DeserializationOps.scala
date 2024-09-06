package co.topl.bridge.consensus.shared.persistence

import co.topl.bridge.consensus.shared.AssetToken
import co.topl.bridge.consensus.shared.GroupToken
import co.topl.bridge.consensus.shared.Lvl
import co.topl.bridge.consensus.shared.SeriesToken
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
import co.topl.bridge.consensus.protobuf.BlockchainEvent.Event.BtcFundsWithdrawn
import co.topl.bridge.consensus.protobuf.BlockchainEvent.Event.Empty
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
import co.topl.bridge.consensus.protobuf.{
  BifrostCurrencyUnit => BifrostCurrencyUnitPb
}
import co.topl.bridge.consensus.protobuf.{BlockchainEvent => BlockchainEventPb}
import org.bitcoins.core.currency.Satoshis
import quivr.models.Int128
import scodec.bits.ByteVector
import co.topl.bridge.consensus.subsystems.monitor.{
  BifrostFundsDeposited,
  BlockchainEvent,
  BTCFundsWithdrawn,
  BTCFundsDeposited,
  BifrostFundsWithdrawn,
  SkippedToplBlock,
  SkippedBTCBlock,
  NewToplBlock,
  NewBTCBlock
}

trait DeserializationOps {

  def fromProtobuf(someAmount: Option[BifrostCurrencyUnitPb]) =
    someAmount match {
      case Some(amount) =>
        amount.currency match {
          case LvlCurrency(value) =>
            Lvl(Int128(value.amount))
          case SeriesTokenCurrency(value) =>
            SeriesToken(
              value.id,
              Int128(value.amount)
            )
          case GroupTokenCurrency(value) =>
            GroupToken(
              value.id,
              Int128(value.amount)
            )
          case AssetTokenCurrency(value) =>
            AssetToken(
              value.groupId,
              value.seriesId,
              Int128(value.amount)
            )
          case _ => throw new IllegalStateException("Unknown currency type")
        }
      case None => throw new IllegalStateException("Amount is missing")
    }

  def fromProtobuf(blockchainEventPb: BlockchainEventPb): BlockchainEvent =
    blockchainEventPb.event match {
      case BtcFundsDepositedEvent(value) =>
        BTCFundsDeposited(
          value.fundsDepositedHeight,
          value.address,
          value.txId,
          value.vout,
          Satoshis.fromBytes(ByteVector(value.amount.toByteArray()))
        )
      case SkippedBTCBlockEvent(value) =>
        SkippedBTCBlock(value.height)
      case BifrostFundsDepositedEvent(value) =>
        BifrostFundsDeposited(
          value.currentToplBlockHeight,
          value.address,
          value.utxoTxId,
          value.utxoIndex,
          fromProtobuf(value.amount)
        )
      case NewToplBlockEvent(value) =>
        NewToplBlock(value.height)
      case SkippedToplBlockEvent(value) =>
        SkippedToplBlock(value.height)
      case BifrostFundsWithdrawnEvent(value) =>
        BifrostFundsWithdrawn(
          value.currentToplBlockHeight,
          value.txId,
          value.txIndex,
          value.secret,
          fromProtobuf(value.amount)
        )
      case NewBTCBlockEvent(value) =>
        NewBTCBlock(value.height)
      case Empty =>
        throw new IllegalStateException("Empty event")
      case BtcFundsWithdrawn(value) =>
        BTCFundsWithdrawn(value.txId, value.vout)
    }
}
