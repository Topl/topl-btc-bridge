package co.topl.bridge

import co.topl.shared.BitcoinNetworkIdentifiers
import scopt.OParser

trait BridgeParamsDescriptor {

  import co.topl.shared.ParamParser._

  val builder = OParser.builder[ToplBTCBridgeParamConfig]

  val parser = {
    import builder._

    OParser.sequence(
      programName("topl-btc-bridge"),
      head("topl-btc-bridge", "0.1"),
      opt[BitcoinNetworkIdentifiers]('n', "network")
        .action((x, c) => c.copy(btcNetwork = x))
        .text(
          "Network name: Possible values: mainnet, testnet, regtest. (mandatory)"
        )
    )
  }

}
