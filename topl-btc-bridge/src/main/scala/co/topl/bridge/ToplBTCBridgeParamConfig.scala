package co.topl.bridge

import co.topl.shared.RegTest
import co.topl.shared.BitcoinNetworkIdentifiers

case class ToplBTCBridgeParamConfig(
    blockToRecover: Int =
      100, // the number of blocks to wait before the user can reclaim their funds
    pegInSeedFile: String = "pegin-wallet.json",
    pegInPassword: String = "password",
    walletSeedFile: String = "wallet.json",
    walletPassword: String = "password",
    btcNetwork: BitcoinNetworkIdentifiers = RegTest
)
