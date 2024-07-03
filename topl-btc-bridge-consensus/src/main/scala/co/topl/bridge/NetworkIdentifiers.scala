package co.topl.bridge

import co.topl.brambl.constants.NetworkConstants

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

sealed abstract class ToplNetworkIdentifiers(
    val i: Int,
    val name: String,
    val networkId: Int
) {
  override def toString: String = name
}

case object ToplNetworkIdentifiers {

  def values = Set(ToplMainnet, ToplTestnet, ToplPrivatenet)

  def fromString(s: String): Option[ToplNetworkIdentifiers] = {
    s match {
      case "mainnet" => Some(ToplMainnet)
      case "testnet" => Some(ToplTestnet)
      case "private" => Some(ToplPrivatenet)
      case _         => None
    }
  }
}

case object ToplMainnet
    extends ToplNetworkIdentifiers(
      0,
      "mainnet",
      NetworkConstants.MAIN_NETWORK_ID
    )
case object ToplTestnet
    extends ToplNetworkIdentifiers(
      1,
      "testnet",
      NetworkConstants.TEST_NETWORK_ID
    )
case object ToplPrivatenet
    extends ToplNetworkIdentifiers(
      2,
      "private",
      NetworkConstants.PRIVATE_NETWORK_ID
    )
