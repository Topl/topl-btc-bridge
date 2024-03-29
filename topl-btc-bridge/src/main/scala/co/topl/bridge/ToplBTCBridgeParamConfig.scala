package co.topl.bridge

import co.topl.shared.RegTest
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.ToplNetworkIdentifiers
import co.topl.shared.ToplPrivatenet

case class ToplBTCBridgeParamConfig(
    blockToRecover: Int =
      100, // the number of blocks to wait before the user can reclaim their funds
    pegInSeedFile: String = "pegin-wallet.json",
    pegInPassword: String = "password",
    walletSeedFile: String = "wallet.json",
    walletPassword: String = "password",
    toplWalletSeedFile: String = "topl-wallet.json",
    toplWalletPassword: String = "password",
    toplWalletDb: String = "topl-wallet.db",
    btcNetwork: BitcoinNetworkIdentifiers = RegTest,
    toplNetwork: ToplNetworkIdentifiers = ToplPrivatenet,
    toplHost: String = "localhost",
    toplPort: Int = 9084,
    toplSecureConnection: Boolean = false
)
