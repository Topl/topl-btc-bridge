package co.topl.bridge

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Ref
import co.topl.brambl.monitoring.BitcoinMonitor
import co.topl.bridge.BridgeParamsDescriptor
import co.topl.bridge.ServerConfig
import co.topl.bridge.ToplBTCBridgeParamConfig
import co.topl.bridge.managers.BTCWalletImpl
import co.topl.bridge.modules.AppModule
import co.topl.shared.utils.KeyGenerationUtils
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.rpc.config.BitcoindAuthCredentials
import org.http4s.ember.server.EmberServerBuilder
import scopt.OParser
import cats.effect.std.Queue
import co.topl.bridge.managers.SessionEvent

case class SystemGlobalState(
    currentStatus: Option[String],
    currentError: Option[String],
    isReady: Boolean = false
)

sealed trait PeginSessionState

case object PeginSessionState {
  case object PeginSessionStateWaitingForBTC extends PeginSessionState
  case class PeginSessionStateMintingTBTC(amount: Long)
      extends PeginSessionState
  case object PeginSessionWaitingForRedemption extends PeginSessionState
}

object Main extends IOApp with BridgeParamsDescriptor with AppModule {

  override def run(args: List[String]): IO[ExitCode] = {
    OParser.parse(
      parser,
      args,
      ToplBTCBridgeParamConfig(
        toplHost = Option(System.getenv("TOPL_HOST")).getOrElse("localhost"),
        toplWalletDb = System.getenv("TOPL_WALLET_DB")
      )
    ) match {
      case Some(config) =>
        runWithArgs(config)
      case None =>
        IO.consoleForIO.errorln("Invalid arguments") *>
          IO(ExitCode.Error)
    }
  }

  private def loadKeyPegin(
      params: ToplBTCBridgeParamConfig
  ): IO[BIP39KeyManager] =
    KeyGenerationUtils.loadKeyManager[IO](
      params.btcNetwork,
      params.pegInSeedFile,
      params.pegInPassword
    )

  private def loadKeyWallet(
      params: ToplBTCBridgeParamConfig
  ): IO[BIP39KeyManager] =
    KeyGenerationUtils.loadKeyManager[IO](
      params.btcNetwork,
      params.walletSeedFile,
      params.walletPassword
    )

  def runWithArgs(params: ToplBTCBridgeParamConfig): IO[ExitCode] = {

    (for {
      pegInKm <- loadKeyPegin(params)
      walletKm <- loadKeyWallet(params)
      pegInWalletManager <- BTCWalletImpl.make[IO](pegInKm)
      walletManager <- BTCWalletImpl.make[IO](walletKm)
      logger =
        org.typelevel.log4cats.slf4j.Slf4jLogger.getLoggerFromName[IO]("App")
      globalState <- Ref[IO].of(
        SystemGlobalState(Some("Setting up wallet..."), None)
      )
      queue <- Queue.unbounded[IO, SessionEvent]
      appAndInitAndStateMachine <- createApp(
        params,
        "self",
        "default",
        queue,
        pegInWalletManager,
        walletManager,
        logger,
        globalState
      )
      credentials = BitcoindAuthCredentials.PasswordBased(
        params.btcUser,
        params.btcPassword
      )
      (app, init, peginStateMachine) = appAndInitAndStateMachine
      bitcoindInstance = BitcoinMonitor.Bitcoind.remoteConnection(
        params.btcNetwork.btcNetwork,
        params.btcUrl,
        credentials
      )
      monitor <- BitcoinMonitor(bitcoindInstance)
      _ <- IO.asyncForIO
        .background(
          fs2.Stream
            .fromQueueUnterminated(queue)
            .evalMap(x => peginStateMachine.innerStateConfigurer(x))
            .compile
            .drain
        )
        .allocated
      _ <- IO.asyncForIO
        .background(
          monitor
            .monitorBlocks()
            .evalMap(x => peginStateMachine.handleNewBlock(x.block))
            .compile
            .drain
        )
        .allocated
      _ <- EmberServerBuilder
        .default[IO]
        .withIdleTimeout(ServerConfig.idleTimeOut)
        .withHost(ServerConfig.host)
        .withPort(ServerConfig.port)
        .withHttpApp(app)
        .withLogger(logger)
        .build
        .allocated
        .both(init.setupWallet())
    } yield {
      Right(
        s"Server started on ${ServerConfig.host}:${ServerConfig.port}"
      )
    }).handleErrorWith { e =>
      e.printStackTrace()
      IO(Left(e.getMessage))
    } >> IO.never

  }
}
