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
import co.topl.bridge.services.SessionManagerAlgebra
import co.topl.bridge.services.SessionManagerImpl
import co.topl.bridge.services.StartSessionModule
import co.topl.shared.BitcoinNetworkIdentifiers
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.staticcontent.resourceServiceBuilder
import scopt.OParser

import java.util.concurrent.ConcurrentHashMap
import co.topl.shared.utils.KeyGenerationUtils
import co.topl.bridge.services.BTCWalletImpl
import co.topl.bridge.services.BTCWalletAlgebra

object Main
    extends IOApp
    with BridgeParamsDescriptor
    with StartSessionModule
    with ConfirmRedemptionModule {

  def apiServices(
      sessionManager: SessionManagerAlgebra[IO],
      pegInWalletManager: BTCWalletAlgebra[IO],
      walletManager: BTCWalletAlgebra[IO],
      btcNetwork: BitcoinNetworkIdentifiers
  ) = HttpRoutes.of[IO] {
    case req @ POST -> Root / "start-session" =>
      startSession(
        req,
        pegInWalletManager,
        sessionManager,
        btcNetwork
      )
    case req @ POST -> Root / "confirm-redemption" =>
      confirmRedemption(
        req,
        pegInWalletManager,
        walletManager,
        sessionManager
      )
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
      pegInKm <- Resource.make(
        KeyGenerationUtils.loadKeyManager[IO](
          params.btcNetwork,
          params.pegInSeedFile,
          params.pegInPassword
        )
      )(_ => IO.unit)
      walletKm <- Resource.make(
        KeyGenerationUtils.loadKeyManager[IO](
          params.btcNetwork,
          params.walletSeedFile,
          params.walletPassword
        )
      )(_ => IO.unit)
      app = {
        val sessionManager =
          SessionManagerImpl.make[IO](new ConcurrentHashMap())
        val pegInWalletManager = BTCWalletImpl.make[IO](pegInKm)
        val walletManager = BTCWalletImpl.make[IO](walletKm)
        val router = Router.define(
          "/" -> apiServices(
            sessionManager,
            pegInWalletManager,
            walletManager,
            params.btcNetwork
          )
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
