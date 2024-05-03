package co.topl.bridge

import co.topl.shared.BitcoinNetworkIdentifiers
import scopt.OParser
import co.topl.shared.ToplNetworkIdentifiers

trait BridgeParamsDescriptor {

  import co.topl.shared.ParamParser._

  val builder = OParser.builder[ToplBTCBridgeParamConfig]

  val parser = {
    import builder._

    OParser.sequence(
      programName("topl-btc-bridge"),
      head("topl-btc-bridge", "0.1"),
      opt[BitcoinNetworkIdentifiers]("btc-network")
        .action((x, c) => c.copy(btcNetwork = x))
        .text(
          "Network name: Possible values: mainnet, testnet, regtest. (mandatory)"
        ),
      opt[ToplNetworkIdentifiers]("topl-network")
        .action((x, c) => c.copy(toplNetwork = x))
        .text(
          "Network name: Possible values: mainnet, testnet, private. (mandatory)"
        ),
      opt[Int]("blocks-to-recover")
        .action((x, c) => c.copy(blockToRecover = x))
        .text(
          "The number of blocks that the user needs to wait before they can reclaim their funds. (default: 100)"
        ),
      opt[String]("topl-wallet-seed-file")
        .action((x, c) => c.copy(toplWalletSeedFile = x))
        .text(
          "The path to the tolp wallet seed file. (default: topl-wallet.json)"
        ),
      opt[String]("topl-wallet-password")
        .action((x, c) => c.copy(toplWalletPassword = x))
        .text(
          "The password to the topl seed file. (default: password)"
        ),
      opt[String]("topl-wallet-db")
        .action((x, c) => c.copy(toplWalletDb = x))
        .text(
          "The topl wallet db. (default: topl-wallet.db)"
        ),
      opt[String]("peg-in-seed-file")
        .action((x, c) => c.copy(pegInSeedFile = x))
        .text(
          "The path to the peg in seed file. (default: pegin-wallet.json)"
        ),
      opt[String]("peg-in-password")
        .action((x, c) => c.copy(pegInPassword = x))
        .text(
          "The password to the seed file. (default: password)"
        ),
      opt[String]("seed-file")
        .action((x, c) => c.copy(walletSeedFile = x))
        .text(
          "The path to the seed file. (default: wallet.json)"
        ),
      opt[String]("password")
        .action((x, c) => c.copy(walletPassword = x))
        .text(
          "The password to the seed file. (default: password)"
        ),
      opt[String]("topl-host")
        .action((x, c) => c.copy(toplHost = x))
        .text("The host of the Topl node. (mandatory)")
        .validate(x =>
          if (x.trim().isEmpty) failure("Topl node host may not be empty")
          else success
        ),
      opt[String]("btc-url")
        .action((x, c) => c.copy(btcUrl = x))
        .text("The url of the Bitcoin node. (mandatory)")
        .validate(x =>
          if (x.trim().isEmpty) failure("Bitcoin node url may not be empty")
          else success
        ),
      opt[String]("btc-user")
        .action((x, c) => c.copy(btcUser = x))
        .text("The username for the Bitcoin node. (mandatory)")
        .validate(x =>
          if (x.trim().isEmpty)
            failure("Bitcoin node username may not be empty")
          else success
        ),
      opt[String]("btc-password")
        .action((x, c) => c.copy(btcPassword = x))
        .text("The password for the Bitcoin node. (mandatory)")
        .validate(x =>
          if (x.trim().isEmpty)
            failure("Bitcoin node password may not be empty")
          else success
        ),
      opt[Int]("topl-port")
        .action((x, c) => c.copy(toplPort = x))
        .text("Port for Topl node. (mandatory)")
        .validate(x =>
          if (x >= 0 && x <= 65536) success
          else failure("Port must be between 0 and 65536")
        ),
      opt[Boolean]("topl-secure")
        .action((x, c) => c.copy(toplSecureConnection = x))
        .text("Enables the secure connection to the node. (optional)")
    )
  }

}
