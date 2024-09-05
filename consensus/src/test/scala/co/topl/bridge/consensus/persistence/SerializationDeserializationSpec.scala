package co.topl.bridge.consensus.persistence

import co.topl.bridge.consensus.shared.AssetToken
import co.topl.bridge.consensus.shared.GroupToken
import co.topl.bridge.consensus.shared.Lvl
import co.topl.bridge.consensus.shared.SeriesToken
import munit.CatsEffectSuite
import org.bitcoins.core.currency.Satoshis
import co.topl.bridge.consensus.subsystems.monitor.{
  BifrostFundsDeposited,
  BTCFundsWithdrawn,
  BTCFundsDeposited,
  BifrostFundsWithdrawn,
  SkippedToplBlock,
  SkippedBTCBlock,
  NewToplBlock,
  NewBTCBlock
}
import co.topl.bridge.consensus.core.persistence.{
  SerializationOps,
  DeserializationOps
}

class SerializationDeserializationSpec
    extends CatsEffectSuite
    with SerializationOps
    with DeserializationOps {

  test("Serialization and Deserialization of BTCFundsWithdrawn") {
    val event = BTCFundsWithdrawn("txId", 1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of NewBTCBlock") {
    val event = NewBTCBlock(1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of SkippedBTCBlock") {
    val event = SkippedBTCBlock(1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of SkippedToplBlock") {
    val event = SkippedToplBlock(1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of NewToplBlock") {
    val event = NewToplBlock(1)
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of BTCFundsDeposited") {
    val event = BTCFundsDeposited(1, "scriptPubKey", "txId", 1, Satoshis(1))
    assertEquals(fromProtobuf(toProtobuf(event)), event)
  }

  test("Serialization and Deserialization of BifrostFundsDeposited") {
    import co.topl.brambl.syntax._
    val eventLvl = BifrostFundsDeposited(1, "address", "utxoTxId", 1, Lvl(1L))
    assertEquals(fromProtobuf(toProtobuf(eventLvl)), eventLvl)
    val eventSeriesToken =
      BifrostFundsDeposited(1, "address", "utxoTxId", 1, SeriesToken("id", 1L))
    assertEquals(fromProtobuf(toProtobuf(eventSeriesToken)), eventSeriesToken)
    val eventGroupToken =
      BifrostFundsDeposited(1, "address", "utxoTxId", 1, GroupToken("id", 1L))
    assertEquals(fromProtobuf(toProtobuf(eventGroupToken)), eventGroupToken)
    val eventAssetToken = BifrostFundsDeposited(
      1,
      "address",
      "utxoTxId",
      1,
      AssetToken("groupId", "seriesId", 1L)
    )
    assertEquals(fromProtobuf(toProtobuf(eventAssetToken)), eventAssetToken)
  }

  test("Serialization and Deserialization of BifrostFundsWithdrawn") {
    import co.topl.brambl.syntax._
    val eventLvl = BifrostFundsWithdrawn(1L, "txId", 1, "secret", Lvl(1))
    assertEquals(fromProtobuf(toProtobuf(eventLvl)), eventLvl)
    val eventSeriesToken =
      BifrostFundsWithdrawn(1L, "txId", 1, "secret", SeriesToken("id", 1L))
    assertEquals(fromProtobuf(toProtobuf(eventSeriesToken)), eventSeriesToken)
    val eventGroupToken =
      BifrostFundsWithdrawn(1L, "txId", 1, "secret", GroupToken("id", 1L))
    assertEquals(fromProtobuf(toProtobuf(eventGroupToken)), eventGroupToken)
    val eventAssetToken = BifrostFundsWithdrawn(
      1L,
      "txId",
      1,
      "secret",
      AssetToken("groupId", "seriesId", 1L)
    )
    assertEquals(fromProtobuf(toProtobuf(eventAssetToken)), eventAssetToken)
  }

}
