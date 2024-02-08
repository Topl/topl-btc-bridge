package co.topl.shared


sealed abstract class BitcoinNetworkIdentifiers(
    val name: String
) {
  override def toString: String = name
  def btcNetwork: org.bitcoins.core.config.BitcoinNetwork =
    this match {
      case Mainnet => org.bitcoins.core.config.MainNet
      case Testnet => org.bitcoins.core.config.TestNet3
      case RegTest => org.bitcoins.core.config.RegTest
    }
}

case object Mainnet extends BitcoinNetworkIdentifiers("mainnet")
case object Testnet extends BitcoinNetworkIdentifiers("testnet")
case object RegTest extends BitcoinNetworkIdentifiers("regtest")
case object BitcoinNetworkIdentifiers {

  def values = Set(Mainnet, Testnet, RegTest)

  def fromString(s: String): Option[BitcoinNetworkIdentifiers] =
    s match {
      case "mainnet" => Some(Mainnet)
      case "testnet" => Some(Testnet)
      case "regtest" => Some(RegTest)
      case _         => None
    }
}
