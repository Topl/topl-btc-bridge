package co.topl.bridge.publicapi

import scopt.OParser
import java.io.File

trait PublicApiParamsDescriptor {

  val builder = OParser.builder[ToplBTCBridgePublicApiParamConfig]

  val parser = {
    import builder._

    OParser.sequence(
      programName("topl-btc-bridge-public-api"),
      head("topl-btc-bridge-public-api", "0.1"),
      opt[File]("config-file")
        .action((x, c) => c.copy(configurationFile = x))
        .validate(x =>
          if (x.exists) success
          else failure("Configuration file does not exist")
        )
        .text(
          "Configuration file for the topl-btc-bridge-public-api service"
        )
    )
  }

}
