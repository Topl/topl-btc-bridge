package co.topl.tbcli
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.RegTest

sealed abstract class ToplBTCCLICommand

case class InitSession(
    seedFile: String = "",
    password: String = "",
    secret: String = ""
) extends ToplBTCCLICommand

case class ToplBTCCLIParamConfig(
    btcNetwork: BitcoinNetworkIdentifiers = RegTest,
    command: Option[ToplBTCCLICommand] = None
)
