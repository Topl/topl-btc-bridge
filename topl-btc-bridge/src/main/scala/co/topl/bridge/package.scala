package co.topl

import _root_.quivr.models.Int128

package object bridge {

  class BTCWaitExpirationTime(val underlying: Int) extends AnyVal
  class ToplWaitExpirationTime(val underlying: Int) extends AnyVal

  object implicits {
    // implicit class for BTCWaitExpirationTime that implements <, >, <=, >=, ==, != with integers
    implicit class BTCWaitExpirationTimeOps(
        val btcWaitExpirationTime: BTCWaitExpirationTime
    ) {
      def <(other: Long): Boolean = btcWaitExpirationTime.underlying < other
      def >(other: Long): Boolean = btcWaitExpirationTime.underlying > other
      def <=(other: Long): Boolean = btcWaitExpirationTime.underlying <= other
      def >=(other: Long): Boolean = btcWaitExpirationTime.underlying >= other
      def ==(other: Long): Boolean = btcWaitExpirationTime.underlying == other
      def !=(other: Long): Boolean = btcWaitExpirationTime.underlying != other
    }

    // implicit class for ToplWaitExpirationTime that implements <, >, <=, >=, ==, != with integers
    implicit class ToplWaitExpirationTimeOps(
        val toplWaitExpirationTime: ToplWaitExpirationTime
    ) {
      def <(other: Long): Boolean = toplWaitExpirationTime.underlying < other
      def >(other: Long): Boolean = toplWaitExpirationTime.underlying > other
      def <=(other: Long): Boolean = toplWaitExpirationTime.underlying <= other
      def >=(other: Long): Boolean = toplWaitExpirationTime.underlying >= other
      def ==(other: Long): Boolean = toplWaitExpirationTime.underlying == other
      def !=(other: Long): Boolean = toplWaitExpirationTime.underlying != other
    }
  }

  class Fellowship(val underlying: String) extends AnyVal

  class Template(val underlying: String) extends AnyVal

  sealed trait BifrostCurrencyUnit {
    val amount: Int128
  }

  case class Lvl(amount: Int128) extends BifrostCurrencyUnit
  case class SeriesToken(id: String, amount: Int128) extends BifrostCurrencyUnit
  case class GroupToken(id: String, amount: Int128) extends BifrostCurrencyUnit
  case class AssetToken(groupId: String, seriesId: String, amount: Int128)
      extends BifrostCurrencyUnit

}
