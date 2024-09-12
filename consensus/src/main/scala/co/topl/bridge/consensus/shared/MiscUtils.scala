package co.topl.bridge.consensus.shared

import co.topl.bridge.consensus.shared.SessionInfo
import co.topl.bridge.consensus.shared.PeginSessionInfo

object MiscUtils {
  import monocle.macros.GenPrism
  val sessionInfoPeginPrism = GenPrism[SessionInfo, PeginSessionInfo]
}
