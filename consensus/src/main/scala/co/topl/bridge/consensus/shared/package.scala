package co.topl.bridge.consensus

import quivr.models.Int128

package object shared {

  sealed trait BifrostCurrencyUnit {
    val amount: Int128
  }

  case class Lvl(amount: Int128) extends BifrostCurrencyUnit
  case class SeriesToken(id: String, amount: Int128) extends BifrostCurrencyUnit
  case class GroupToken(id: String, amount: Int128) extends BifrostCurrencyUnit
  case class AssetToken(groupId: String, seriesId: String, amount: Int128)
      extends BifrostCurrencyUnit

  class BTCConfirmationThreshold(val underlying: Int) extends AnyVal

  class BTCRetryThreshold(val underlying: Int) extends AnyVal

  class BTCWaitExpirationTime(val underlying: Int) extends AnyVal

  class ToplWaitExpirationTime(val underlying: Int) extends AnyVal

  class ToplConfirmationThreshold(val underlying: Int) extends AnyVal



}
