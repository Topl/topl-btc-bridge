package co.topl.bridge

import co.topl.shared.RegTest
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.ToplNetworkIdentifiers
import co.topl.shared.ToplPrivatenet
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.currency.SatoshisLong

case class ToplBTCBridgeParamConfig(
    btcWaitExpirationTime: Int =
      100, // the number of blocks to wait before the user can reclaim their funds
    pegInSeedFile: String = "pegin-wallet.json",
    pegInPassword: String = "password",
    walletSeedFile: String = "wallet.json",
    walletPassword: String = "password",
    toplWalletSeedFile: String = "topl-wallet.json",
    toplWalletPassword: String = "password",
    toplWalletDb: String = "topl-wallet.db",
    btcUrl: String = "http://localhost",
    btcUser: String = "bitcoin",
    zmqHost: String = "localhost",
    zmqPort: Int = 28332,
    btcPassword: String = "password",
    btcNetwork: BitcoinNetworkIdentifiers = RegTest,
    toplNetwork: ToplNetworkIdentifiers = ToplPrivatenet,
    toplHost: String = "localhost",
    toplPort: Int = 9084,
    mintingFee: Long = 10,
    feePerByte: CurrencyUnit = 2.satoshis,
    toplSecureConnection: Boolean = false
)
