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
import co.topl.brambl.monitoring.BifrostMonitor
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
import co.topl.bridge.consensus.persistence.StorageApiImpl
import co.topl.bridge.consensus.monitor.BlockProcessor
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
import co.topl.shared.ReplicaNode
import cats.effect.kernel.Sync
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import io.grpc.Metadata
import co.topl.shared.ReplicaCount
import co.topl.bridge.consensus.persistence.StorageApi

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

  def withName(s: String): Option[PeginSessionState] = s match {
    case "PeginSessionStateWaitingForBTC" =>
      Some(PeginSessionStateWaitingForBTC)
    case "PeginSessionStateMintingTBTC" => Some(PeginSessionStateMintingTBTC)
    case "PeginSessionWaitingForRedemption" =>
      Some(PeginSessionWaitingForRedemption)
    case "PeginSessionWaitingForClaim" => Some(PeginSessionWaitingForClaim)
    case "PeginSessionMintingTBTCConfirmation" =>
      Some(PeginSessionMintingTBTCConfirmation)
    case "PeginSessionWaitingForEscrowBTCConfirmation" =>
      Some(PeginSessionWaitingForEscrowBTCConfirmation)
    case "PeginSessionWaitingForClaimBTCConfirmation" =>
      Some(PeginSessionWaitingForClaimBTCConfirmation)
    case _ => None
  }
}

object Main
    extends IOApp
    with ConsensusParamsDescriptor
    with AppModule
    with InitUtils {

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
      replicaId: ReplicaId,
      replicaCount: ReplicaCount
  ): Resource[F, Map[ClientId, (PublicApiClientGrpc[F], PublicKey)]] = {
    import cats.implicits._
    (for (i <- 0 until replicaCount.value) yield {
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

  private def loadReplicaNodeFromConfig[F[_]: Sync: Logger](
      conf: Config
  )(implicit replicaCount: ReplicaCount): F[List[ReplicaNode[F]]] = {
    import cats.implicits._
    (for (i <- 0 until replicaCount.value) yield {
      for {
        host <- Sync[F].delay(
          conf.getString(s"bridge.replica.consensus.replicas.$i.host")
        )
        port <- Sync[F].delay(
          conf.getInt(s"bridge.replica.consensus.replicas.$i.port")
        )
        secure <- Sync[F].delay(
          conf.getBoolean(s"bridge.replica.consensus.replicas.$i.secure")
        )
        _ <-
          info"bridge.replica.consensus.replicas.$i.host: ${host}"
        _ <-
          info"bridge.replica.consensus.replicas.$i.port: ${port}"
        _ <-
          info"bridge.replica.consensus.replicas.$i.secure: ${secure}"
      } yield ReplicaNode[F](i, host, port, secure)
    }).toList.sequence
  }

  private def createReplicaClienMap[F[_]: Async](
      replicaNodes: List[ReplicaNode[F]]
  ) = {
    import cats.implicits._
    import fs2.grpc.syntax.all._
    for {
      idClientList <- (for {
        replicaNode <- replicaNodes
      } yield {
        for {
          channel <-
            (if (replicaNode.backendSecure)
               ManagedChannelBuilder
                 .forAddress(replicaNode.backendHost, replicaNode.backendPort)
                 .useTransportSecurity()
             else
               ManagedChannelBuilder
                 .forAddress(replicaNode.backendHost, replicaNode.backendPort)
                 .usePlaintext()).resource[F]
          consensusClient <- StateMachineServiceFs2Grpc.stubResource(
            channel
          )
        } yield (replicaNode.id -> consensusClient)
      }).sequence.map(x => Map(x: _*))
    } yield idClientList
  }

  def initializeForResources(
      storageApi: StorageApi[IO],
      idReplicaClientMap: Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
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
      currentView: Ref[IO, Long],
      currentState: Ref[IO, SystemGlobalState]
  )(implicit
      replicaId: ReplicaId,
      replicaCount: ReplicaCount,
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
      storageApi,
      idReplicaClientMap,
      params,
      publicApiClientGrpcMap,
      queue,
      walletManager,
      pegInWalletManager,
      logger,
      currentBitcoinNetworkHeight,
      currentToplHeight,
      currentView,
      currentState
    )
  } yield (
    currentToplHeightVal,
    currentBitcoinNetworkHeightVal,
    res._1,
    res._2,
    res._3
  )

  def startResources(
      privateKeyFile: String,
      params: ToplBTCBridgeConsensusParamConfig,
      queue: Queue[IO, SessionEvent],
      walletManager: BTCWalletAlgebra[IO],
      pegInWalletManager: BTCWalletAlgebra[IO],
      currentBitcoinNetworkHeight: Ref[IO, Int],
      currentToplHeight: Ref[IO, Long],
      currentView: Ref[IO, Long],
      currentState: Ref[IO, SystemGlobalState]
  )(implicit
      conf: Config,
      fromFellowship: Fellowship,
      fromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      btcRetryThreshold: BTCRetryThreshold,
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId,
      logger: Logger[IO],
      replicaId: ReplicaId,
      replicaCount: ReplicaCount
  ) = {
    import fs2.grpc.syntax.all._
    for {
      replicaKeyPair <- BridgeCryptoUtils
        .getKeyPair[IO](privateKeyFile)
      publicApiClientGrpcMap <- createClientMap(
        replicaKeyPair,
        conf
      )(IO.asyncForIO, logger, replicaId, replicaCount)
      replicaNodes <- loadReplicaNodeFromConfig[IO](conf).toResource
      storageApi <- StorageApiImpl.make[IO](params.dbFile.toPath().toString())
      idReplicaClientMap <- createReplicaClienMap[IO](replicaNodes)
      res <- initializeForResources(
        storageApi,
        idReplicaClientMap,
        publicApiClientGrpcMap,
        params,
        queue,
        walletManager,
        pegInWalletManager,
        currentBitcoinNetworkHeight,
        currentToplHeight,
        currentView,
        currentState
      ).toResource
      (
        currentToplHeightVal,
        currentBitcoinNetworkHeightVal,
        grpcServiceResource,
        init,
        peginStateMachine
      ) = res
      bifrostQueryAlgebra = BifrostQueryAlgebra
        .make[IO](
          channelResource(
            params.toplHost,
            params.toplPort,
            params.toplSecureConnection
          )
        )
      btcMonitor <- BitcoinMonitor(
        bitcoindInstance,
        zmqHost = params.zmqHost,
        zmqPort = params.zmqPort
      )
      bifrostMonitor <- BifrostMonitor(
        params.toplHost,
        params.toplPort,
        params.toplSecureConnection,
        bifrostQueryAlgebra
      )
      _ <- storageApi.initializeStorage().toResource
      grpcService <- grpcServiceResource
      _ <- getAndSetCurrentToplHeight(
        currentToplHeight,
        bifrostQueryAlgebra
      ).toResource
      _ <- getAndSetCurrentBitcoinHeight(
        currentBitcoinNetworkHeight,
        bitcoindInstance
      ).toResource
      _ <- getAndSetCurrentToplHeight( // we do this again in case the BTC height took too much time to get
        currentToplHeight,
        bifrostQueryAlgebra
      ).toResource
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
          btcMonitor
            .either(
              bifrostMonitor
                .handleErrorWith(e => {
                  e.printStackTrace()
                  fs2.Stream.empty
                })
            )
            .flatMap(
              BlockProcessor
                .process(currentBitcoinNetworkHeightVal, currentToplHeightVal)
            )
            .observe(_.foreach(evt => storageApi.insertBlockchainEvent(evt)))
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
      _ <- info"Outcome of monitoring: $outcomeVal".toResource
    } yield ()
  }

  def getAndSetCurrentToplHeight[F[_]: Async: Logger](
      currentToplHeight: Ref[F, Long],
      bqa: BifrostQueryAlgebra[F]
  ) = {
    import cats.implicits._
    import scala.concurrent.duration._
    (for {
      someTip <- bqa.blockByDepth(1)
      height <- someTip
        .map({ tip =>
          val (_, header, _, _) = tip
          currentToplHeight.set(header.height) >>
            info"Obtained and set topl height: ${header.height}" >>
            header.height.pure[F]
        })
        .getOrElse(
          warn"Failed to obtain and set topl height" >> Async[F]
            .sleep(3.second) >> 0L.pure[F]
        )
    } yield height).iterateUntil(_ != 0)
  }

  def getAndSetCurrentBitcoinHeight[F[_]: Async: Logger](
      currentBitcoinNetworkHeight: Ref[F, Int],
      bitcoindInstance: BitcoindRpcClient
  ) = {
    import cats.implicits._
    import scala.concurrent.duration._
    (for {
      height <- Async[F].fromFuture(
        Async[F].delay(bitcoindInstance.getBlockCount())
      )
      _ <- currentBitcoinNetworkHeight.set(height)
      _ <-
        if (height == 0)
          warn"Failed to obtain and set BTC height" >> Async[F].sleep(3.second)
        else info"Obtained and set BTC height: $height"
    } yield height).iterateUntil(_ != 0)
  }

  def runWithArgs(params: ToplBTCBridgeConsensusParamConfig): IO[ExitCode] = {
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
    implicit val conf = ConfigFactory.parseFile(params.configurationFile)
    implicit val replicaId = new ReplicaId(
      conf.getInt("bridge.replica.replicaId")
    )
    implicit val replicaCount =
      new ReplicaCount(conf.getInt("bridge.replica.consensus.replicaCount"))
    implicit val logger =
      org.typelevel.log4cats.slf4j.Slf4jLogger
        .getLoggerFromName[IO]("consensus-" + f"${replicaId.id}%02d")
    (for {
      _ <- IO(Security.addProvider(new BouncyCastleProvider()))
      pegInKm <- loadKeyPegin(params)
      walletKm <- loadKeyWallet(params)
      pegInWalletManager <- BTCWalletImpl.make[IO](pegInKm)
      walletManager <- BTCWalletImpl.make[IO](walletKm)
      _ <- printParams[IO](params)
      _ <- printConfig[IO]
      globalState <- Ref[IO].of(
        SystemGlobalState(Some("Setting up wallet..."), None)
      )
      currentToplHeight <- Ref[IO].of(0L)
      queue <- Queue.unbounded[IO, SessionEvent]
      currentBitcoinNetworkHeight <- Ref[IO].of(0)
      currentView <- Ref[IO].of(0L)
      _ <- startResources(
        privateKeyFile,
        params,
        queue,
        walletManager,
        pegInWalletManager,
        currentBitcoinNetworkHeight,
        currentToplHeight,
        currentView,
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
