package co.topl.bridge.managers

import munit.CatsEffectSuite
import java.util.concurrent.ConcurrentHashMap
import cats.effect.IO
import java.util.UUID

class SessionManagerSpec extends CatsEffectSuite {

  val sessionInfo = SessionInfo(
    "bridgePKey",
    0,
    "userPKey",
    "secretHash",
    "scriptAsm",
    "address"
  )

  test("SessionManagerAlgebra should create and retrieve a session") {
    val sut =
      SessionManagerImpl.make[IO](new ConcurrentHashMap[String, SessionInfo]())
    assertIO(
      for {
        sessionId <- sut.createNewSession(sessionInfo)
        retrievedSession <- sut.getSession(sessionId)
      } yield {
        retrievedSession
      },
      sessionInfo
    )
  }

  test("SessionManagerAlgebra should fail to retrieve a non existing session") {
    val sut =
      SessionManagerImpl.make[IO](new ConcurrentHashMap[String, SessionInfo]())

    assertIO(
      (for {
        _ <- sut.createNewSession(sessionInfo)
        _ <- sut.getSession(UUID.randomUUID().toString)
      } yield {
        true
      }).handleError(_ => false),
      false
    )
  }

}
