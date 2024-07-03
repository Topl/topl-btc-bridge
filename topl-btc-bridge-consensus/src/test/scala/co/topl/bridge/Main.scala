package co.topl.bridge

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Ref
import co.topl.brambl.monitoring.BitcoinMonitor
import co.topl.bridge.BridgeParamsDescriptor
import co.topl.bridge.ServerConfig
import co.topl.bridge.ToplBTCBridgeConsensusParamConfig
import co.topl.bridge.managers.BTCWalletImpl
import co.topl.bridge.modules.AppModule
import co.topl.bridge.utils.KeyGenerationUtils
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.rpc.config.BitcoindAuthCredentials
import org.http4s.ember.server.EmberServerBuilder
import scopt.OParser
import cats.effect.std.Queue
import co.topl.bridge.BTCRetryThreshold
import co.topl.bridge.managers.SessionEvent
import co.topl.brambl.monitoring.BifrostMonitor
import co.topl.brambl.dataApi.BifrostQueryAlgebra
import co.topl.bridge.statemachine.pegin.BlockProcessor
import co.topl.brambl.models.SeriesId
import co.topl.brambl.models.GroupId
import co.topl.brambl.utils.Encoding
import com.google.protobuf.ByteString

case class SystemGlobalState(
    currentStatus: Option[String],
    currentError: Option[String],
    isReady: Boolean = false
)

sealed trait PeginSessionState

case object PeginSessionState {
  case object PeginSessionStateWaitingForBTC extends PeginSessionState
  case object PeginSessionStateMintingTBTC extends PeginSessionState
  case object PeginSessionWaitingForRedemption extends PeginSessionState
  case object PeginSessionWaitingForClaim extends PeginSessionState
  case object PeginSessionMintingTBTCConfirmation extends PeginSessionState
  case object PeginSessionWaitingForEscrowBTCConfirmation
      extends PeginSessionState
  case object PeginSessionWaitingForClaimBTCConfirmation
      extends PeginSessionState
}

object Main extends IOApp with BridgeParamsDescriptor with AppModule {

  override def run(args: List[String]): IO[ExitCode] = {
    OParser.parse(
      parser,
      args,
      ToplBTCBridgeConsensusParamConfig(
        toplHost = Option(System.getenv("TOPL_HOST")).getOrElse("localhost"),
        toplWalletDb = System.getenv("TOPL_WALLET_DB"),
        zmqHost = Option(System.getenv("ZMQ_HOST")).getOrElse("localhost"),
        zmqPort =
          Option(System.getenv("ZMQ_PORT")).map(_.toInt).getOrElse(28332),
        btcUrl = Option(System.getenv("BTC_URL")).getOrElse("http://localhost"),
        btcUser = Option(System.getenv("BTC_USER")).getOrElse("bitcoin"),
        groupId = Option(System.getenv("ABTC_GROUP_ID"))
          .map(Encoding.decodeFromHex(_).toOption)
          .flatten
          .map(x => GroupId(ByteString.copyFrom(x)))
          .getOrElse(GroupId(ByteString.copyFrom(Array.fill(32)(0.toByte)))),
        seriesId = Option(System.getenv("ABTC_SERIES_ID"))
          .map(Encoding.decodeFromHex(_).toOption)
          .flatten
          .map(x => SeriesId(ByteString.copyFrom(x)))
          .getOrElse(SeriesId(ByteString.copyFrom(Array.fill(32)(0.toByte)))),
        btcPassword =
          Option(System.getenv("BTC_PASSWORD")).getOrElse("password")
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
      params: ToplBTCBridgeConsensusParamConfig
  ): IO[BIP39KeyManager] =
    KeyGenerationUtils.loadKeyManager[IO](
      params.btcNetwork,
      params.btcPegInSeedFile,
      params.btcPegInPassword
    )

  private def loadKeyWallet(
      params: ToplBTCBridgeConsensusParamConfig
  ): IO[BIP39KeyManager] =
    KeyGenerationUtils.loadKeyManager[IO](
      params.btcNetwork,
      params.btcWalletSeedFile,
      params.walletPassword
    )

  def runWithArgs(params: ToplBTCBridgeConsensusParamConfig): IO[ExitCode] = {
    import org.typelevel.log4cats.syntax._
    implicit val defaultFromFellowship = new Fellowship("self")
    implicit val defaultFromTemplate = new Template("default")
    val credentials = BitcoindAuthCredentials.PasswordBased(
      params.btcUser,
      params.btcPassword
    )
    implicit val bitcoindInstance = BitcoinMonitor.Bitcoind.remoteConnection(
      params.btcNetwork.btcNetwork,
      params.btcUrl,
      credentials
    )
    implicit val groupId = params.groupId
    implicit val seriesId = params.seriesId
    implicit val btcRetryThreshold: BTCRetryThreshold = new BTCRetryThreshold(
      params.btcRetryThreshold
    )
    (for {
      pegInKm <- loadKeyPegin(params)
      walletKm <- loadKeyWallet(params)
      pegInWalletManager <- BTCWalletImpl.make[IO](pegInKm)
      walletManager <- BTCWalletImpl.make[IO](walletKm)
      logger =
        org.typelevel.log4cats.slf4j.Slf4jLogger
          .getLoggerFromName[IO]("btc-bridge")
      // For each parameter, log its value to info
      _ <- info"Command line arguments" (logger)
      _ <- info"btc-blocks-to-recover  : ${params.btcWaitExpirationTime}" (
        logger
      )
      _ <- info"topl-blocks-to-recover : ${params.toplWaitExpirationTime}" (
        logger
      )
      _ <-
        info"btc-confirmation-threshold : ${params.btcConfirmationThreshold}" (
          logger
        )
      _ <- info"btc-peg-in-seed-file   : ${params.btcPegInSeedFile}" (logger)
      _ <- info"btc-peg-in-password    : ******" (logger)
      _ <- info"wallet-seed-file       : ${params.btcWalletSeedFile}" (logger)
      _ <- info"wallet-password        : ******" (logger)
      _ <- info"topl-wallet-seed-file  : ${params.toplWalletSeedFile}" (logger)
      _ <- info"topl-wallet-password   : ******" (logger)
      _ <- info"topl-wallet-db         : ${params.toplWalletDb}" (logger)
      _ <- info"btc-url                : ${params.btcUrl}" (logger)
      _ <- info"btc-user               : ${params.btcUser}" (logger)
      _ <- info"zmq-host               : ${params.zmqHost}" (logger)
      _ <- info"zmq-port               : ${params.zmqPort}" (logger)
      _ <- info"btc-password           : ******" (logger)
      _ <- info"btc-network            : ${params.btcNetwork}" (logger)
      _ <- info"topl-network           : ${params.toplNetwork}" (logger)
      _ <- info"topl-host              : ${params.toplHost}" (logger)
      _ <- info"topl-port              : ${params.toplPort}" (logger)
      _ <- info"topl-secure-connection : ${params.toplSecureConnection}" (
        logger
      )
      _ <- info"minting-fee            : ${params.mintingFee}" (logger)
      _ <- info"fee-per-byte           : ${params.feePerByte}" (logger)
      _ <- info"abtc-group-id          : ${Encoding.encodeToHex(params.groupId.value.toByteArray)}" (
        logger
      )
      _ <- info"abtc-series-id         : ${Encoding.encodeToHex(params.seriesId.value.toByteArray)}" (
        logger
      )
      globalState <- Ref[IO].of(
        SystemGlobalState(Some("Setting up wallet..."), None)
      )
      currentToplHeight <- Ref[IO].of(0L)
      queue <- Queue.unbounded[IO, SessionEvent]
      currentBitcoinNetworkHeight <- Ref[IO].of(0)
      appAndInitAndStateMachine <- createApp(
        params,
        queue,
        walletManager,
        pegInWalletManager,
        logger,
        currentBitcoinNetworkHeight,
        currentToplHeight,
        globalState
      )
      (app, init, peginStateMachine) = appAndInitAndStateMachine
      monitor <- BitcoinMonitor(
        bitcoindInstance,
        zmqHost = params.zmqHost,
        zmqPort = params.zmqPort
      )
      bifrostQueryAlgebra = BifrostQueryAlgebra.make[IO](
        channelResource(
          params.toplHost,
          params.toplPort,
          params.toplSecureConnection
        )
      )
      bifrostMonitor <- BifrostMonitor(
        bifrostQueryAlgebra
      )
      _ <- IO.asyncForIO
        .background(
          fs2.Stream
            .fromQueueUnterminated(queue)
            .evalMap(x => peginStateMachine.innerStateConfigurer(x))
            .compile
            .drain
        )
        .allocated
      currentToplHeightVal <- currentToplHeight.get
      currentBitcoinNetworkHeightVal <- currentBitcoinNetworkHeight.get
      _ <- IO.asyncForIO
        .background(
          monitor
            .monitorBlocks()
            .either(bifrostMonitor.monitorBlocks())
            .flatMap(
              BlockProcessor
                .process(currentBitcoinNetworkHeightVal, currentToplHeightVal)
            )
            .flatMap(
              // this handles each event in the context of the state machine
              peginStateMachine.handleBlockchainEventInContext
            )
            .evalMap(identity)
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
        .both(
          init.setupWallet(
            defaultFromFellowship,
            defaultFromTemplate,
            params.groupId,
            params.seriesId
          )
        )
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
