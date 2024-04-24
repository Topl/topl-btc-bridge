package co.topl.bridge

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import co.topl.bridge.BridgeParamsDescriptor
import co.topl.bridge.ServerConfig
import co.topl.bridge.ToplBTCBridgeParamConfig
import co.topl.bridge.managers.BTCWalletImpl
import co.topl.shared.utils.KeyGenerationUtils
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.http4s.ember.server.EmberServerBuilder
import scopt.OParser
import cats.effect.kernel.Ref
import co.topl.bridge.modules.AppModule

case class SystemGlobalState(
    currentStatus: Option[String],
    currentError: Option[String],
    isReady: Boolean = false
)

sealed trait MintingBTCState

case object MintingBTCState {
  case object MintingBTCStateReady extends MintingBTCState
  case object MintingBTCStateMinting extends MintingBTCState
  case object MintingBTCStateWaiting extends MintingBTCState
  case object MintingBTCStateMinted extends MintingBTCState
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
      appAndInit <- createApp(
        params,
        pegInWalletManager,
        walletManager,
        logger,
        globalState
      )
      (app, init) = appAndInit
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
