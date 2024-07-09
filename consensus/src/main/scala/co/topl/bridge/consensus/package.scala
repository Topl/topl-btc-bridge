package co.topl.bridge

import _root_.quivr.models.Int128

package object consensus {

  class BTCRetryThreshold(val underlying: Int) extends AnyVal
  class BTCWaitExpirationTime(val underlying: Int) extends AnyVal
  class ToplWaitExpirationTime(val underlying: Int) extends AnyVal
  class BTCConfirmationThreshold(val underlying: Int) extends AnyVal


  class Fellowship(val underlying: String) extends AnyVal

  class Template(val underlying: String) extends AnyVal

  sealed trait BifrostCurrencyUnit {
    val amount: Int128
  }

  
  case class ReplicaId(
      id: Int
  )
  case class ClientId(
      id: Int
  )
  
  case class ConsensusClientMessageId(
      timestamp: Long
  )


  case class Lvl(amount: Int128) extends BifrostCurrencyUnit
  case class SeriesToken(id: String, amount: Int128) extends BifrostCurrencyUnit
  case class GroupToken(id: String, amount: Int128) extends BifrostCurrencyUnit
  case class AssetToken(groupId: String, seriesId: String, amount: Int128)
      extends BifrostCurrencyUnit

}
