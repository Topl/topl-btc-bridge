package co.topl.bridge.consensus.core.utils

import co.topl.bridge.consensus.core.managers.SessionInfo
import co.topl.bridge.consensus.core.managers.PeginSessionInfo

object MiscUtils {
  import monocle.macros.GenPrism
  val sessionInfoPeginPrism = GenPrism[SessionInfo, PeginSessionInfo]
}
