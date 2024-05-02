package co.topl.bridge.utils

import co.topl.bridge.managers.SessionInfo
import co.topl.bridge.managers.PeginSessionInfo

object MiscUtils {
  import monocle.macros.GenPrism
  val sessionInfoPeginPrism = GenPrism[SessionInfo, PeginSessionInfo]
}
