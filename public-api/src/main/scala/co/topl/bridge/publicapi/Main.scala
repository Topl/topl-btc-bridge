package co.topl.bridge.publicapi

import cats.data.Kleisli
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.shared.ClientId
import co.topl.shared.ConsensusClientGrpc
import co.topl.bridge.publicapi.modules.ApiServicesModule
import co.topl.shared.modules.ReplyServicesModule
import co.topl.shared.BridgeCryptoUtils
import co.topl.shared.BridgeError
import co.topl.shared.BridgeResponse
import co.topl.shared.ConsensusClientGrpcImpl
import co.topl.shared.ConsensusClientMessageId
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import fs2.grpc.syntax.all._
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.http4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.http4s.server.staticcontent.resourceServiceBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import scopt.OParser

import java.net.InetSocketAddress
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import co.topl.shared.ReplicaNode
import co.topl.shared.ReplicaCount
import cats.effect.std.Mutex

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

object Main
    extends IOApp
    with PublicApiParamsDescriptor
    with ApiServicesModule
    with ReplyServicesModule {

  def createApp(
      consensusGrpcClients: ConsensusClientGrpc[IO]
  )(implicit
      l: Logger[IO],
      clientNumber: ClientId
  ) = {
    val staticAssetsService = resourceServiceBuilder[IO]("/static").toRoutes
    val router = Router.define(
      "/api" -> apiServices(
        consensusGrpcClients
      )
    )(default = staticAssetsService)
    Kleisli[IO, Request[IO], Response[IO]] { request =>
      router
        .run(request)
        .getOrElse(
          Response[IO](
            status = Status.NotFound,
            body = fs2.Stream.emits(
              """<!DOCTYPE html>
                |<html>
                |<body>
                |<h1>Not found</h1>
                |<p>The page you are looking for is not found.</p>
                |<p>This message was generated on the server.</p>
                |</body>
                |</html>""".stripMargin('|').getBytes()
            ),
            headers = Headers(headers.`Content-Type`(MediaType.text.html))
          )
        )
    }
  }

  def setupServices(
      clientHost: String,
      clientPort: Int,
      privateKeyFile: String,
      replicaNodes: List[ReplicaNode[IO]],
      replicaKeysMap: Map[Int, PublicKey],
      currentViewRef: Ref[IO, Long]
  )(implicit
      replicaCount: ReplicaCount,
      clientNumber: ClientId,
      logger: Logger[IO]
  ) = {
    val messageResponseMap =
      new ConcurrentHashMap[ConsensusClientMessageId, ConcurrentHashMap[Either[
        BridgeError,
        BridgeResponse
      ], LongAdder]]()
    val messageVoterMap =
      new ConcurrentHashMap[
        ConsensusClientMessageId,
        ConcurrentHashMap[Int, Int]
      ]()
    for {
      keyPair <- BridgeCryptoUtils.getKeyPair[IO](privateKeyFile)
      mutex <- Mutex[IO].toResource
      replicaClients <- ConsensusClientGrpcImpl
        .makeContainer(
          currentViewRef,
          keyPair,
          mutex,
          replicaNodes,
          messageVoterMap,
          messageResponseMap
        )
      app = createApp(replicaClients)
      _ <- EmberServerBuilder
        .default[IO]
        .withIdleTimeout(ServerConfig.idleTimeOut)
        .withHost(ServerConfig.host)
        .withPort(ServerConfig.port)
        .withHttpApp(
          CORS.policy.withAllowOriginAll.withAllowMethodsAll
            .withAllowHeadersAll(app)
        )
        .withLogger(logger)
        .build
      rService <- replyService[IO](
        currentViewRef,
        replicaKeysMap,
        messageVoterMap,
        messageResponseMap
      )
      grpcListener <- NettyServerBuilder
        .forAddress(new InetSocketAddress(clientHost, clientPort))
        .addService(rService)
        .resource[IO]
      grpcShutdown <- IO.asyncForIO.background(
        IO(
          grpcListener.start
        ) >> info"Netty-Server (grpc) service bound to address ${clientHost}:${clientPort}"
      )
    } yield grpcShutdown
  }

  private def createReplicaPublicKeyMap[F[_]: Sync](
      conf: Config
  )(implicit replicaCount: ReplicaCount): F[Map[Int, PublicKey]] = {
    (for (i <- 0 until replicaCount.value) yield {
      val publicKeyFile = conf.getString(
        s"bridge.client.consensus.replicas.$i.publicKeyFile"
      )
      for {
        keyPair <- BridgeCryptoUtils.getPublicKey(publicKeyFile).allocated
      } yield (i, keyPair._1)
    }).toList.sequence.map(x => Map(x: _*))
  }

  private def loadReplicaNodeFromConfig[F[_]: Sync: Logger](
      conf: Config
  ): F[List[ReplicaNode[F]]] = {
    val replicaCount = conf.getInt("bridge.client.consensus.replicaCount")
    (for (i <- 0 until replicaCount) yield {
      for {
        host <- Sync[F].delay(
          conf.getString(s"bridge.client.consensus.replicas.$i.host")
        )
        port <- Sync[F].delay(
          conf.getInt(s"bridge.client.consensus.replicas.$i.port")
        )
        secure <- Sync[F].delay(
          conf.getBoolean(s"bridge.client.consensus.replicas.$i.secure")
        )
        _ <-
          info"bridge.client.consensus.replicas.$i.host: ${host}"
        _ <-
          info"bridge.client.consensus.replicas.$i.port: ${port}"
        _ <-
          info"bridge.client.consensus.replicas.$i.secure: ${secure}"
      } yield ReplicaNode[F](i, host, port, secure)
    }).toList.sequence
  }

  override def run(args: List[String]): IO[ExitCode] = {
    // log syntax
    OParser.parse(
      parser,
      args,
      ToplBTCBridgePublicApiParamConfig()
    ) match {
      case Some(configuration) =>
        val conf = ConfigFactory.parseFile(configuration.configurationFile)
        implicit val client = new ClientId(
          conf.getInt("bridge.client.clientId")
        )
        implicit val replicaCount = new ReplicaCount(
          conf.getInt("bridge.client.consensus.replicaCount")
        )
        implicit val logger =
          org.typelevel.log4cats.slf4j.Slf4jLogger
            .getLoggerFromName[IO](s"public-api-" + f"${client.id}%02d")
        for {
          _ <- info"Configuration parameters"
          _ <- IO(Security.addProvider(new BouncyCastleProvider()))
          clientHost <- IO(
            conf.getString("bridge.client.responses.host")
          )
          clientPort <- IO(
            conf.getInt("bridge.client.responses.port")
          )
          privateKeyFile <- IO(
            conf.getString("bridge.client.security.privateKeyFile")
          )
          _ <- info"bridge.client.security.privateKeyFile: ${privateKeyFile}"
          _ <- info"bridge.client.clientId: ${client.id}"
          _ <- info"bridge.client.responses.host: ${clientHost}"
          _ <- info"bridge.client.responses.port: ${clientPort}"
          replicaKeysMap <- createReplicaPublicKeyMap[IO](conf)
          replicaNodes <- loadReplicaNodeFromConfig[IO](conf)
          currentView <- Ref.of[IO, Long](0)
          _ <- setupServices(
            clientHost,
            clientPort,
            privateKeyFile,
            replicaNodes,
            replicaKeysMap,
            currentView
          ).useForever
        } yield ExitCode.Success
      case None =>
        IO.consoleForIO.errorln("Invalid arguments") *>
          IO(ExitCode.Error)
    }
  }
}
