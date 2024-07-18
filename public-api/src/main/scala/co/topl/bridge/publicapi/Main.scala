package co.topl.bridge.publicapi

import cats.data.Kleisli
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import cats.effect.std.CountDownLatch
import cats.implicits._
import co.topl.bridge.publicapi.ClientNumber
import co.topl.bridge.publicapi.modules.ApiServicesModule
import co.topl.bridge.publicapi.modules.ReplyServicesModule
import co.topl.shared.BridgeCryptoUtils
import co.topl.shared.BridgeError
import co.topl.shared.BridgeResponse
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import fs2.grpc.syntax.all._
import io.grpc.ManagedChannelBuilder
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.http4s._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.staticcontent.resourceServiceBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import scopt.OParser

import java.net.InetSocketAddress
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.ConcurrentHashMap
import org.http4s.server.middleware.CORS

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
      consensusGrpc: ConsensusClientGrpc[IO]
  )(implicit
      l: Logger[IO],
      clientNumber: ClientNumber
  ) = {
    val staticAssetsService = resourceServiceBuilder[IO]("/static").toRoutes
    val router = Router.define(
      "/api" -> apiServices(
        consensusGrpc
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

  case class ConsensusClientMessageId(
      timestamp: Long
  )

  def setupServices(
      clientHost: String,
      clientPort: Int,
      privateKeyFile: String,
      backendHost: String,
      backendPort: Int,
      backendSecure: Boolean,
      replicaKeysMap: Map[Int, PublicKey],
      messageResponseMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        Ref[IO, Either[
          BridgeError,
          BridgeResponse
        ]]
      ]
  )(implicit
      clientNumber: ClientNumber,
      logger: org.typelevel.log4cats.Logger[IO]
  ) = {
    val countDownLatchMap = new ConcurrentHashMap[
      ConsensusClientMessageId,
      CountDownLatch[IO]
    ]()
    for {
      keyPair <- BridgeCryptoUtils.getKeyPair[IO](privateKeyFile)
      channel <-
        (if (backendSecure)
           ManagedChannelBuilder
             .forAddress(backendHost, backendPort)
             .useTransportSecurity()
         else
           ManagedChannelBuilder
             .forAddress(backendHost, backendPort)
             .usePlaintext()).resource[IO]

      consensusClient <- ConsensusClientGrpcImpl
        .make[IO](
          keyPair,
          countDownLatchMap,
          messageResponseMap,
          channel
        )
      app = createApp(consensusClient)
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
      rService <- replyService(
        replicaKeysMap,
        messageResponseMap,
        countDownLatchMap
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

  private def createPublicKeyMap[F[_]: Sync](
      conf: Config
  ): F[Map[Int, PublicKey]] = {
    val replicaCount =
      conf.getInt("bridge.client.consensus.replicaCount")
    (for (i <- 0 until replicaCount) yield {
      val publicKeyFile = conf.getString(
        s"bridge.client.consensus.replicas.$i.publicKeyFile"
      )
      for {
        keyPair <- BridgeCryptoUtils.getPublicKey(publicKeyFile).allocated
      } yield (i, keyPair._1)
    }).toList.sequence.map(x => Map(x: _*))
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
        implicit val clientId = new ClientNumber(
          conf.getInt("bridge.client.clientId")
        )
        implicit val logger =
          org.typelevel.log4cats.slf4j.Slf4jLogger
            .getLoggerFromName[IO](s"public-api-" + f"${clientId.value}%02d")
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
          backendHost <- IO(
            conf.getString("bridge.client.consensus.replicas.0.host")
          )
          backendPort <- IO(
            conf.getInt("bridge.client.consensus.replicas.0.port")
          )
          backendSecure <- IO(
            conf.getBoolean("bridge.client.consensus.replicas.0.secure")
          )
          _ <- info"bridge.client.security.privateKeyFile: ${privateKeyFile}"
          _ <- info"bridge.client.consensus.replicas.0.host: ${backendHost}"
          _ <- info"bridge.client.consensus.replicas.0.port: ${backendPort}"
          _ <- info"bridge.client.consensus.replicas.0.secure: ${backendSecure}"
          _ <- info"bridge.client.clientId: ${clientId.value}"
          _ <- info"bridge.client.responses.host: ${clientHost}"
          _ <- info"bridge.client.responses.port: ${clientPort}"
          replicaKeysMap <- createPublicKeyMap[IO](conf)
          messageResponseMap <- IO(
            new ConcurrentHashMap[ConsensusClientMessageId, Ref[IO, Either[
              BridgeError,
              BridgeResponse
            ]]]
          )
          _ <- setupServices(
            clientHost,
            clientPort,
            privateKeyFile,
            backendHost,
            backendPort,
            backendSecure,
            replicaKeysMap,
            messageResponseMap
          ).useForever
        } yield ExitCode.Success
      case None =>
        IO.consoleForIO.errorln("Invalid arguments") *>
          IO(ExitCode.Error)
    }
  }
}
