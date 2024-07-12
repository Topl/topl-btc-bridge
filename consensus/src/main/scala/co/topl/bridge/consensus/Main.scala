package co.topl.bridge.consensus

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import co.topl.brambl.dataApi.BifrostQueryAlgebra
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.brambl.monitoring.BifrostMonitorBis
import co.topl.brambl.monitoring.BitcoinMonitor
import co.topl.brambl.utils.Encoding
import co.topl.bridge.consensus.ConsensusParamsDescriptor
import co.topl.bridge.consensus.ReplicaId
import co.topl.bridge.consensus.ServerConfig
import co.topl.bridge.consensus.ToplBTCBridgeConsensusParamConfig
import co.topl.bridge.consensus.managers.BTCWalletAlgebra
import co.topl.bridge.consensus.managers.BTCWalletImpl
import co.topl.bridge.consensus.managers.SessionEvent
import co.topl.bridge.consensus.modules.AppModule
import co.topl.bridge.consensus.statemachine.pegin.BlockProcessor
import co.topl.bridge.consensus.utils.KeyGenerationUtils
import co.topl.shared.BridgeCryptoUtils
import com.google.protobuf.ByteString
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.ManagedChannelBuilder
import io.grpc.netty.NettyServerBuilder
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.rpc.config.BitcoindAuthCredentials
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import scopt.OParser

import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

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

object Main extends IOApp with ConsensusParamsDescriptor with AppModule {

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
        println("Invalid arguments")
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

  private def createClientMap[F[_]: Async: Logger](
      replicaKeyPair: KeyPair,
      conf: Config
  )(implicit
      replicaId: ReplicaId
  ): Resource[F, Map[ClientId, (PublicApiClientGrpc[F], PublicKey)]] = {
    import cats.implicits._
    val replicaCount =
      conf.getInt("bridge.replica.clients.clientCount")
    (for (i <- 0 until replicaCount) yield {
      val publicKeyFile = conf.getString(
        s"bridge.replica.clients.clients.$i.publicKeyFile"
      )
      val host = conf.getString(s"bridge.replica.clients.clients.$i.host")
      val port = conf.getInt(s"bridge.replica.clients.clients.$i.port")
      val secure = conf.getBoolean(
        s"bridge.replica.clients.clients.$i.secure"
      )
      import fs2.grpc.syntax.all._
      for {
        publicKey <- BridgeCryptoUtils.getPublicKey(publicKeyFile)
        channel <-
          (if (secure)
             ManagedChannelBuilder
               .forAddress(host, port)
               .useTransportSecurity()
           else
             ManagedChannelBuilder
               .forAddress(host, port)
               .usePlaintext()).resource[F]
        publicApiGrpc <- PublicApiClientGrpcImpl.make[F](
          channel,
          replicaKeyPair
        )
      } yield (new ClientId(i) -> (publicApiGrpc, publicKey))
    }).toList.sequence.map(x => Map(x: _*))
  }

  def initializeForResources(
      publicApiClientGrpcMap: Map[
        ClientId,
        (PublicApiClientGrpc[IO], PublicKey)
      ],
      params: ToplBTCBridgeConsensusParamConfig,
      queue: Queue[IO, SessionEvent],
      walletManager: BTCWalletAlgebra[IO],
      pegInWalletManager: BTCWalletAlgebra[IO],
      currentBitcoinNetworkHeight: Ref[IO, Int],
      currentToplHeight: Ref[IO, Long],
      currentState: Ref[IO, SystemGlobalState]
  )(implicit
      fromFellowship: Fellowship,
      fromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      btcRetryThreshold: BTCRetryThreshold,
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId,
      logger: Logger[IO]
  ) = for {
    currentToplHeightVal <- currentToplHeight.get
    currentBitcoinNetworkHeightVal <- currentBitcoinNetworkHeight.get
    res <- createApp(
      params,
      publicApiClientGrpcMap,
      queue,
      walletManager,
      pegInWalletManager,
      logger,
      currentBitcoinNetworkHeight,
      currentToplHeight,
      currentState
    )
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
    bifrostMonitor <- BifrostMonitorBis(
      params.toplHost,
      params.toplPort,
      params.toplSecureConnection,
      bifrostQueryAlgebra
    )
  } yield (
    currentToplHeightVal,
    currentBitcoinNetworkHeightVal,
    res._1,
    res._2,
    res._3,
    monitor,
    bifrostMonitor
  )

  def startResources(
      conf: Config,
      privateKeyFile: String,
      params: ToplBTCBridgeConsensusParamConfig,
      queue: Queue[IO, SessionEvent],
      walletManager: BTCWalletAlgebra[IO],
      pegInWalletManager: BTCWalletAlgebra[IO],
      currentBitcoinNetworkHeight: Ref[IO, Int],
      currentToplHeight: Ref[IO, Long],
      currentState: Ref[IO, SystemGlobalState]
  )(implicit
      fromFellowship: Fellowship,
      fromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      btcRetryThreshold: BTCRetryThreshold,
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId,
      logger: Logger[IO],
      replicaId: ReplicaId
  ) = {
    import fs2.grpc.syntax.all._
    val replicaHost = conf.getString("bridge.replica.requests.host")
    val replicaPort = conf.getInt("bridge.replica.requests.port")
    for {
      replicaKeyPair <- BridgeCryptoUtils
        .getKeyPair[IO](privateKeyFile)
      createClientMapResource <- createClientMap(
        replicaKeyPair,
        conf
      )(IO.asyncForIO, logger, replicaId)
      res <- initializeForResources(
        createClientMapResource,
        params,
        queue,
        walletManager,
        pegInWalletManager,
        currentBitcoinNetworkHeight,
        currentToplHeight,
        currentState
      ).toResource
      (
        currentToplHeightVal,
        currentBitcoinNetworkHeightVal,
        grpcServiceResource,
        init,
        peginStateMachine,
        monitor,
        bifrostMonitorRes
      ) = res
      bifrostMonitor <- bifrostMonitorRes
      grpcService <- grpcServiceResource
      grpcListener <- NettyServerBuilder
        .forAddress(new InetSocketAddress(replicaHost, replicaPort))
        .addService(grpcService)
        .resource[IO]
      _ <- IO.asyncForIO
        .background(
          fs2.Stream
            .fromQueueUnterminated(queue)
            .evalMap(x => peginStateMachine.innerStateConfigurer(x))
            .compile
            .drain
        )
      _ <- IO.asyncForIO
        .background(
          IO(
            grpcListener.start
          ) >> info"Netty-Server (grpc) service bound to address ${replicaHost}:${replicaPort}" (
            logger
          )
        )
      outcome <- IO.asyncForIO
        .backgroundOn(
          monitor
            .monitorBlocks()
            .either(
              bifrostMonitor
                .monitorBlocks()
                .handleErrorWith(e => {
                  println("Error in bifrost monitor")
                  e.printStackTrace()
                  fs2.Stream.empty
                })
            )
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
            .drain,
          ExecutionContext.fromExecutor(Executors.newFixedThreadPool(4))
        )
      outcomeVal <- outcome.toResource
      _ <- info"Outcome of monitoring: $outcomeVal" (logger).toResource
    } yield ()
  }

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
    val conf = ConfigFactory.parseFile(params.configurationFile)
    implicit val replicaId = new ReplicaId(
      conf.getInt("bridge.replica.replicaId")
    )
    implicit val logger =
      org.typelevel.log4cats.slf4j.Slf4jLogger
        .getLoggerFromName[IO]("consensus-" + f"${replicaId.id}%02d")
    val replicaHost = conf.getString("bridge.replica.requests.host")
    val replicaPort = conf.getInt("bridge.replica.requests.port")
    (for {
      _ <- IO(Security.addProvider(new BouncyCastleProvider()))
      pegInKm <- loadKeyPegin(params)
      walletKm <- loadKeyWallet(params)
      pegInWalletManager <- BTCWalletImpl.make[IO](pegInKm)
      walletManager <- BTCWalletImpl.make[IO](walletKm)
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
      _ <-
        info"topl-confirmation-threshold : ${params.toplConfirmationThreshold}" (
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
      _ <- info"config-file            : ${params.configurationFile.toPath().toString()}" (
        logger
      )
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
      privateKeyFile <- IO(
        conf.getString("bridge.replica.security.privateKeyFile")
      )
      _ <- info"bridge.replica.security.privateKeyFile: ${privateKeyFile}" (
        logger
      )
      _ <- info"bridge.replica.requests.host: ${replicaHost}" (logger)
      _ <- info"bridge.replica.requests.port: ${replicaPort}" (logger)
      _ <- info"bridge.replica.replicaId: ${replicaId.id}" (logger)
      globalState <- Ref[IO].of(
        SystemGlobalState(Some("Setting up wallet..."), None)
      )
      currentToplHeight <- Ref[IO].of(0L)
      queue <- Queue.unbounded[IO, SessionEvent]
      currentBitcoinNetworkHeight <- Ref[IO].of(0)
      _ <- startResources(
        conf,
        privateKeyFile,
        params,
        queue,
        walletManager,
        pegInWalletManager,
        currentBitcoinNetworkHeight,
        currentToplHeight,
        globalState
      ).useForever
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
