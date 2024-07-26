package co.topl.bridge

package object publicapi {

  class ClientNumber(val value: Int) extends AnyVal

  case class ConsensusClientMessageId(
      timestamp: Long
  )

}
