package co.topl.bridge

import cats.data.Kleisli
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import co.topl.bridge.BridgeParamsDescriptor
import co.topl.bridge.ServerConfig
import co.topl.bridge.ToplBTCBridgeParamConfig
import co.topl.bridge.services.ConfirmRedemptionModule
import co.topl.bridge.services.StartSessionModule
import co.topl.shared.BitcoinNetworkIdentifiers
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.staticcontent.resourceServiceBuilder
import scopt.OParser
import co.topl.bridge.services.SessionInfo
import co.topl.bridge.services.SessionManager
import co.topl.bridge.services.WalletManager
import co.topl.bridge.services.BTCWallet
import scala.collection.mutable

object Main
    extends IOApp
    with BridgeParamsDescriptor
    with StartSessionModule
    with ConfirmRedemptionModule {

  def apiServices(
      mapRes: Resource[IO, mutable.Map[String, SessionInfo]],
      btcWallet: Resource[IO, BTCWallet],
      btcNetwork: BitcoinNetworkIdentifiers
  ) = HttpRoutes.of[IO] {
    case req @ POST -> Root / "start-session" =>
      startSession(req, mapRes, btcNetwork)
    case req @ POST -> Root / "confirm-redemption" =>
      confirmRedemption(req, mapRes, btcWallet, btcNetwork)
  }

  override def run(args: List[String]): IO[ExitCode] = {
    OParser.parse(parser, args, ToplBTCBridgeParamConfig()) match {
      case Some(config) =>
        runWithArgs(config)
      case None =>
        println("Invalid arguments")
        IO(ExitCode.Error)
    }
  }

  def runWithArgs(params: ToplBTCBridgeParamConfig): IO[ExitCode] = {

    val staticAssetsService = resourceServiceBuilder[IO]("/static").toRoutes
    (for {
      notFoundResponse <- Resource.make(
        NotFound(
          """<!DOCTYPE html>
          |<html>
          |<body>
          |<h1>Not found</h1>
          |<p>The page you are looking for is not found.</p>
          |<p>This message was generated on the server.</p>
          |</body>
          |</html>""".stripMargin('|'),
          headers.`Content-Type`(MediaType.text.html)
        )
      )(_ => IO.unit)
      app = {
        val map = mutable.Map.empty[String, SessionInfo]
        val mapRes = SessionManager.createSessionMap(map)
        val walletManager = WalletManager.createWallet()
        val router = Router.define(
          "/" -> apiServices(mapRes, walletManager, params.btcNetwork)
        )(default = staticAssetsService)

        Kleisli[IO, Request[IO], Response[IO]] { request =>
          router.run(request).getOrElse(notFoundResponse)
        }
      }
      logger =
        org.typelevel.log4cats.slf4j.Slf4jLogger.getLoggerFromName[IO]("App")
      _ <- EmberServerBuilder
        .default[IO]
        .withIdleTimeout(ServerConfig.idleTimeOut)
        .withHost(ServerConfig.host)
        .withPort(ServerConfig.port)
        .withHttpApp(app)
        .withLogger(logger)
        .build
    } yield {
      Right(
        s"Server started on ${ServerConfig.host}:${ServerConfig.port}"
      )
    }).allocated
      .map(_._1)
      .handleErrorWith { e =>
        IO {
          Left(e.getMessage)
        }
      } >> IO.never

  }
}
