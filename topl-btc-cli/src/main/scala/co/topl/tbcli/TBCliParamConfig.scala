package co.topl.tbcli
// import co.topl.bridge.BitcoinNetworkIdentifiers
// import co.topl.bridge.RegTest

sealed abstract class ToplBTCCLICommand

case class InitSession(
    seedFile: String = "",
    password: String = "",
    secret: String = ""
) extends ToplBTCCLICommand

// case class ToplBTCCLIParamConfig(
//     btcNetwork: BitcoinNetworkIdentifiers = RegTest,
//     command: Option[ToplBTCCLICommand] = None
// )
