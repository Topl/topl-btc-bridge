package co.topl.bridge

import co.topl.shared.RegTest
import co.topl.shared.BitcoinNetworkIdentifiers

case class ToplBTCBridgeParamConfig(
    blockNb: Int =
      1000, // the number of blocks to wait before the user can reclaim their funds
    btcNetwork: BitcoinNetworkIdentifiers = RegTest
)
