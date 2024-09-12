package co.topl.bridge.consensus.managers

import cats.effect.IO
import cats.effect.std.Queue
import co.topl.bridge.consensus.core.PeginSessionState
import co.topl.bridge.consensus.core.persistence.StorageApi
import co.topl.bridge.consensus.core.persistence.StorageApiImpl
import munit.CatsEffectSuite

import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID
import cats.effect.kernel.Resource
import org.typelevel.log4cats.SelfAwareStructuredLogger
import co.topl.bridge.consensus.core.managers.{SessionEvent, PeginSessionInfo}

class SessionManagerSpec extends CatsEffectSuite {

  val sessionInfo = PeginSessionInfo(
    0,
    0,
    "mintTemplateName",
    "redeemAddress",
    "escrowAddress",
    "scriptAsm",
    "sha256",
    1,
    100,
    "claimAddress",
    PeginSessionState.PeginSessionStateWaitingForBTC
  )
  implicit val logger: SelfAwareStructuredLogger[IO] =
    org.typelevel.log4cats.slf4j.Slf4jLogger
      .getLoggerFromName[IO]("test")

  val testdb = "test.db"

  val cleanupDir = ResourceFunFixture[StorageApi[IO]](
    for {
      _ <- Resource
        .make(IO(Files.delete(Paths.get(testdb))).handleError(_ => ()))(_ =>
          IO(Files.delete(Paths.get(testdb)))
        )
      storageApi <- StorageApiImpl.make[IO](testdb)
      _ <- storageApi.initializeStorage().toResource
    } yield storageApi
  )

  cleanupDir.test(
    "SessionManagerAlgebra should create and retrieve a session"
  ) { storageApi =>
    assertIO(
      for {
        queue <- Queue.unbounded[IO, SessionEvent]
        sut = co.topl.bridge.consensus.core.managers.SessionManagerImpl.makePermanent[IO](
          storageApi,
          queue
        )
        sessionId <- IO(UUID.randomUUID().toString)
        _ <- sut.createNewSession(sessionId, sessionInfo)
        retrievedSession <- sut.getSession(sessionId)
      } yield {
        retrievedSession
      },
      Some(sessionInfo)
    )
  }

  cleanupDir.test(
    "SessionManagerAlgebra should fail to retrieve a non existing session"
  ) { storageApi =>
    assertIO(
      for {
        queue <- Queue.unbounded[IO, SessionEvent]
        sut = co.topl.bridge.consensus.core.managers.SessionManagerImpl.makePermanent[IO](
          storageApi,
          queue
        )
        sessionId <- IO(UUID.randomUUID().toString)
        _ <- sut.createNewSession(sessionId, sessionInfo)
        res <- sut.getSession(UUID.randomUUID().toString)
      } yield {
        res
      },
      None
    )
  }

}
