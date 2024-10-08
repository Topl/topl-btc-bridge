package co.topl.bridge.consensus.core

import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.currency.SatoshisLong
import co.topl.brambl.models.SeriesId
import co.topl.brambl.models.GroupId
import java.io.File

case class ToplBTCBridgeConsensusParamConfig(
    btcWaitExpirationTime: Int =
      100, // the number of blocks to wait before the user can reclaim their funds
    btcConfirmationThreshold: Int =
      6, // the number of confirmations required for a peg-in transaction
    toplWaitExpirationTime: Int =
      2000, // the number of blocks to wait before the user can reclaim their funds
    toplConfirmationThreshold: Int =
      6, // the number of confirmations required for a peg-out transaction
    checkpointInterval: Int = 100, // the number of requests between checkpoints
    kWatermark: Int = 200, // the gap between the low and high watermark
    btcPegInSeedFile: String = "pegin-wallet.json",
    btcPegInPassword: String = "password",
    btcWalletSeedFile: String = "wallet.json",
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
    btcRetryThreshold: Int = 6,
    mintingFee: Long = 10,
    feePerByte: CurrencyUnit = 2.satoshis,
    groupId: GroupId,
    seriesId: SeriesId,
    configurationFile: File = new File("application.conf"),
    dbFile: File = new File("bridge.db"),
    toplSecureConnection: Boolean = false
)
