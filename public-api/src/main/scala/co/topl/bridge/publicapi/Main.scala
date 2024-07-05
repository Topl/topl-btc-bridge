package co.topl.bridge.publicapi

import cats.data.Kleisli
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import co.topl.bridge.consensus.service.Empty
import co.topl.bridge.consensus.service.ResponseServiceFs2Grpc
import co.topl.bridge.consensus.service.StartSessionOperation
import co.topl.bridge.consensus.service.StateMachineReply
import co.topl.bridge.publicapi.ClientNumber
import co.topl.shared.BridgeContants
import co.topl.shared.StartPeginSessionRequest
import com.typesafe.config.ConfigFactory
import fs2.grpc.syntax.all._
import io.circe.generic.auto._
import io.grpc.Metadata
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.circe._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.staticcontent.resourceServiceBuilder
import org.typelevel.log4cats.syntax._
import scopt.OParser

import java.net.InetSocketAddress
import java.security.Security
import cats.effect.kernel.Resource
import cats.effect.std.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import cats.effect.kernel.Sync
import cats.effect.kernel.Async

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

object Main extends IOApp with PublicApiParamsDescriptor {

  def apiServices(consensusGrpc: ConsensusClientGrpc[IO])(implicit
      clientNumber: ClientNumber
  ) = {
    import org.http4s.dsl.io._
    HttpRoutes.of[IO] {
      case req @ POST -> Root / BridgeContants.START_PEGIN_SESSION_PATH =>
        implicit val startSessionRequestDecoder
            : EntityDecoder[IO, StartPeginSessionRequest] =
          jsonOf[IO, StartPeginSessionRequest]

        for {
          x <- req.as[StartPeginSessionRequest]
          _ <- consensusGrpc.startPegin(
            StartSessionOperation(
              x.pkey,
              x.sha256
            )
          )
          res <- Ok("")
        } yield res

      case req @ POST -> Root / BridgeContants.TOPL_MINTING_STATUS =>
        ???
    }
  }

  def createApp(consensusGrpc: ConsensusClientGrpc[IO])(implicit
      clientNumber: ClientNumber
  ) = {
    val staticAssetsService = resourceServiceBuilder[IO]("/static").toRoutes
    val router = Router.define(
      "/api" -> apiServices(consensusGrpc)
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

  def replyService[F[_]: Async](
      messagesMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        CountDownLatch[F]
      ]
  ) =
    ResponseServiceFs2Grpc.bindServiceResource(
      serviceImpl = new ResponseServiceFs2Grpc[F, Metadata] {
        def deliverResponse(
            request: StateMachineReply,
            ctx: Metadata
        ): F[Empty] = {
          ???
        }
      }
    )

  case class ConsensusClientMessageId(
      timestamp: Long
  )

  def setupServices(
      clientHost: String,
      clientPort: Int,
      privateKeyFile: String,
      backendHost: String,
      backendPort: Int,
      backendSecure: Boolean
  )(implicit
      clientNumber: ClientNumber,
      logger: org.typelevel.log4cats.Logger[IO]
  ) = for {
    concurrentHashMap <- Resource.make(
      IO(
        new ConcurrentHashMap[
          ConsensusClientMessageId,
          CountDownLatch[IO]
        ]()
      )
    )(_ => IO.unit)
    consensusClient <- ConsensusClientGrpcImpl
      .make[IO](
        privateKeyFile,
        concurrentHashMap,
        backendHost,
        backendPort,
        backendSecure
      )
    app = createApp(consensusClient)
    _ <- EmberServerBuilder
      .default[IO]
      .withIdleTimeout(ServerConfig.idleTimeOut)
      .withHost(ServerConfig.host)
      .withPort(ServerConfig.port)
      .withHttpApp(app)
      .withLogger(logger)
      .build
    rService <- replyService(concurrentHashMap)
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

  override def run(args: List[String]): IO[ExitCode] = {
    implicit val logger =
      org.typelevel.log4cats.slf4j.Slf4jLogger
        .getLoggerFromName[IO]("public-api")
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
        for {
          _ <- IO(Security.addProvider(new BouncyCastleProvider()))
          _ <- info"Configuration parameters"
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
          _ <- setupServices(
            clientHost,
            clientPort,
            privateKeyFile,
            backendHost,
            backendPort,
            backendSecure
          ).useForever
        } yield ExitCode.Success
      case None =>
        IO.consoleForIO.errorln("Invalid arguments") *>
          IO(ExitCode.Error)
    }
  }
}
