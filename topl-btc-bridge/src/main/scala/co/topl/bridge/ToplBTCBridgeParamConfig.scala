package co.topl.bridge

import co.topl.shared.RegTest
import co.topl.shared.BitcoinNetworkIdentifiers

case class ToplBTCBridgeParamConfig(
    blockToRedeem: Int =
      100, // the number of blocks to wait before the user can reclaim their funds
    seedFile: String = "wallet.json",
    password: String = "password",
    btcNetwork: BitcoinNetworkIdentifiers = RegTest
)
