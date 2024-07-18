package co.topl.bridge.consensus.utils

import co.topl.bridge.consensus.managers.SessionInfo
import co.topl.bridge.consensus.managers.PeginSessionInfo

object MiscUtils {
  import monocle.macros.GenPrism
  val sessionInfoPeginPrism = GenPrism[SessionInfo, PeginSessionInfo]
}
