package co.topl.bridge.managers

import munit.CatsEffectSuite
import java.util.concurrent.ConcurrentHashMap
import cats.effect.IO
import java.util.UUID
import co.topl.bridge.PeginSessionState
import cats.effect.std.Queue

class SessionManagerSpec extends CatsEffectSuite {

  val sessionInfo = PeginSessionInfo(
    0,
    0,
    "mintTemplateName",
    "redeemAddress",
    "escrowAddress",
    "scriptAsm",
    "toplBridgePKey",
    "sha256",
    "claimAddress",
    PeginSessionState.PeginSessionStateWaitingForBTC
  )

  test("SessionManagerAlgebra should create and retrieve a session") {
    assertIO(
      for {
        queue <- Queue.unbounded[IO, SessionEvent]
        sut = SessionManagerImpl.make[IO](
          queue,
          new ConcurrentHashMap[String, SessionInfo]()
        )
        sessionId <- sut.createNewSession(sessionInfo)
        retrievedSession <- sut.getSession(sessionId)
      } yield {
        retrievedSession
      },
      Some(sessionInfo)
    )
  }

  test("SessionManagerAlgebra should fail to retrieve a non existing session") {
    assertIO(
      for {
        queue <- Queue.unbounded[IO, SessionEvent]
        sut = SessionManagerImpl.make[IO](
          queue,
          new ConcurrentHashMap[String, SessionInfo]()
        )
        _ <- sut.createNewSession(sessionInfo)
        res <- sut.getSession(UUID.randomUUID().toString)
      } yield {
        res
      },
      None
    )
  }

}
