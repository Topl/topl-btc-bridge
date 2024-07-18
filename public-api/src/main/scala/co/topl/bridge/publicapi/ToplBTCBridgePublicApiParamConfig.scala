package co.topl.bridge.publicapi

import java.io.File

case class ToplBTCBridgePublicApiParamConfig(
    configurationFile: File = new File("application.conf")
)
