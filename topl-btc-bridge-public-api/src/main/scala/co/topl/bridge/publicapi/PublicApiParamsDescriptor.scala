package co.topl.bridge.publicapi

import scopt.OParser

trait PublicApiParamsDescriptor {

  val builder = OParser.builder[ToplBTCBridgePublicApiParamConfig]

  val parser = {
    import builder._

    OParser.sequence(
      programName("topl-btc-bridge-public-api"),
      head("topl-btc-bridge-public-api", "0.1"),
      opt[String]("config-file")
        .action((x, c) => c.copy(configurationFile = x))
        .text(
          "Network name: Possible values: mainnet, testnet, regtest. (mandatory)"
        )
    )
  }

}
