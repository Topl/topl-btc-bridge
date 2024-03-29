package co.topl.bridge.controllers

import cats.effect.IO
import co.topl.brambl.cli.mockbase.BaseTransactionBuilderApi
import co.topl.brambl.cli.mockbase.BaseWalletStateAlgebra
import co.topl.brambl.models.Indices
import co.topl.brambl.models.LockAddress
import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.brambl.models.box.Lock
import co.topl.brambl.models.transaction.IoTransaction
import co.topl.bridge.MintingBTCState
import co.topl.bridge.managers.PeginSessionInfo
import co.topl.bridge.managers.SessionInfo
import co.topl.bridge.managers.SessionManagerImpl
import co.topl.bridge.stubs.BaseToplWalletAlgebra
import co.topl.bridge.stubs.BaseTransactionAlgebra
import co.topl.bridge.stubs.UnitTestStubs
import co.topl.shared.ConfirmDepositRequest
import com.google.protobuf.ByteString
import io.circe.Json
import munit.CatsEffectSuite
import quivr.models.KeyPair

import java.util.concurrent.ConcurrentHashMap

class ConfirmDepositControllerSpec extends CatsEffectSuite with SharedData {

  import co.topl.bridge.stubs.UnitTestStubs._

  val transactionBuilderApi = new BaseTransactionBuilderApi[IO]() {
    override def lockAddress(lock: Lock): IO[LockAddress] = IO(lockAddress01)
  }

  val walletStateAlgebra = new BaseWalletStateAlgebra[IO]() {
    override def getCurrentIndicesForFunds(
        fellowship: String,
        template: String,
        someInteraction: Option[Int]
    ): IO[Option[Indices]] = IO.pure(Some(Indices(1, 1, 1)))

    override def getLockByAddress(
        lockAddress: String
    ): IO[Option[Lock.Predicate]] = IO.pure(Some(lock01))

    override def getLockByIndex(indices: Indices): IO[Option[Lock.Predicate]] =
      IO.pure(Some(lock01))

  }

  val logger =
    org.typelevel.log4cats.slf4j.Slf4jLogger.getLoggerFromName[IO]("App")

  val confirmDepositController = new ConfirmDepositController[IO](
    walletStateAlgebra,
    transactionBuilderApi
  )(IO.asyncForIO, logger)

  test(
    "ConfirmDepositController should succeed on valid input"
  ) {

    val sessionManager = SessionManagerImpl.make[IO](
      new ConcurrentHashMap[String, SessionInfo]()
    )
    assertIOBoolean(
      for {
        sessionId <- sessionManager.createNewSession(
          PeginSessionInfo(
            1,
            "mintTemplateName",
            "ptetP7jshHUwFCmPv2JdE75DfvENZKu3cJ36cNummadfxDxcK9ckBWU5J8GK",
            "scriptAsm",
            MintingBTCState.MintingBTCStateReady
          )
        )
        keyPair <- walletManagementUtils.loadKeys(
          toplWalletFile,
          testToplPassword
        )
        res <- confirmDepositController.confirmDeposit(
          keyPair,
          ConfirmDepositRequest(
            sessionId,
            1000L
          ),
          new BaseToplWalletAlgebra[IO]() {
            override def createSimpleAssetMintingTransactionFromParams(
                keyPair: KeyPair,
                fromFellowship: String,
                fromTemplate: String,
                someFromInteraction: Option[Int],
                fee: Long,
                ephemeralMetadata: Option[Json],
                commitment: Option[ByteString],
                assetMintingStatement: AssetMintingStatement,
                redeemLockAddress: String
            ): IO[IoTransaction] = IO.pure(
              UnitTestStubs.iotransaction01
            )
          },
          new BaseTransactionAlgebra[IO](),
          UnitTestStubs.makeGenusQueryAlgebraMockWithAddress[IO],
          10L,
          sessionManager
        )
      } yield {
        res.isRight
      }
    )
  }

}
