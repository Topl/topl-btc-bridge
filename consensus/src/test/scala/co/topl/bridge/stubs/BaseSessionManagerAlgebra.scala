package co.topl.bridge.stubs

import cats.effect.IO
import co.topl.bridge.consensus.shared.PeginSessionInfo
import co.topl.bridge.consensus.shared.PeginSessionState
import co.topl.bridge.consensus.shared.SessionInfo
import co.topl.bridge.consensus.subsystems.monitor.SessionManagerAlgebra

class BaseSessionManagerAlgebra extends SessionManagerAlgebra[IO] {

  override def createNewSession(
      sessionId: String,
      sessionInfo: SessionInfo
  ): IO[String] = ???

  override def getSession(sessionId: String): IO[Option[SessionInfo]] = ???

  override def updateSession(
      sessionId: String,
      sessionInfoTransformer: PeginSessionInfo => SessionInfo
  ): IO[Option[SessionInfo]] = ???

  override def removeSession(
      sessionId: String,
      finalState: PeginSessionState
  ): IO[Unit] = ???

}
