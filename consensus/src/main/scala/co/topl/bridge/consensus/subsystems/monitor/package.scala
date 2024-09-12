package co.topl.bridge.consensus.subsystems

import _root_.co.topl.brambl.models.box.Value
import _root_.co.topl.brambl.utils.Encoding
import _root_.co.topl.bridge.consensus.shared.AssetToken
import _root_.co.topl.bridge.consensus.shared.GroupToken
import _root_.co.topl.bridge.consensus.shared.Lvl
import _root_.co.topl.bridge.consensus.shared.SeriesToken

package object monitor {

  def isLvlSeriesGroupOrAsset(value: Value.Value): Boolean = {
    value.isLvl || value.isSeries || value.isGroup || value.isAsset
  }

  def toCurrencyUnit(value: Value.Value) = {
    assert(isLvlSeriesGroupOrAsset(value))
    if (value.isLvl)
      Lvl(value.lvl.get.quantity)
    else if (value.isSeries)
      SeriesToken(
        Encoding.encodeToBase58(
          value.series.get.seriesId.value.toByteArray()
        ),
        value.series.get.quantity
      )
    else if (value.isGroup)
      GroupToken(
        Encoding.encodeToBase58(
          value.group.get.groupId.value.toByteArray()
        ),
        value.group.get.quantity
      )
    else
      AssetToken(
        Encoding.encodeToBase58(
          value.asset.get.groupId.get.value.toByteArray()
        ),
        Encoding.encodeToBase58(
          value.asset.get.seriesId.get.value
            .toByteArray()
        ),
        value.asset.get.quantity
      )
  }
}
