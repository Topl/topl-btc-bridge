package co.topl.bridge.consensus.core.utils

import co.topl.bridge.consensus.subsystems.monitor.SessionInfo
import co.topl.bridge.consensus.subsystems.monitor.PeginSessionInfo

object MiscUtils {
  import monocle.macros.GenPrism
  val sessionInfoPeginPrism = GenPrism[SessionInfo, PeginSessionInfo]
}
