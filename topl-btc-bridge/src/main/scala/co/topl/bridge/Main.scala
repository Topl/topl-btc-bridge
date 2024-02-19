package co.topl.bridge

import cats.data.Kleisli
import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.constants.NetworkConstants
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.RpcChannelResource
import co.topl.brambl.servicekit.WalletKeyApi
import co.topl.brambl.servicekit.WalletStateApi
import co.topl.brambl.servicekit.WalletStateResource
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.BridgeParamsDescriptor
import co.topl.bridge.ServerConfig
import co.topl.bridge.ToplBTCBridgeParamConfig
import co.topl.bridge.controllers.ConfirmRedemptionController
import co.topl.bridge.controllers.StartSessionController
import co.topl.bridge.controllers.ConfirmDepositController
import co.topl.bridge.managers.BTCWalletAlgebra
import co.topl.bridge.managers.BTCWalletImpl
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.managers.SessionManagerImpl
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.bridge.managers.ToplWalletImpl
import co.topl.bridge.managers.TransactionAlgebra
import co.topl.bridge.managers.WalletManagementUtils
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.BridgeError
import co.topl.shared.ConfirmDepositRequest
import co.topl.shared.ConfirmRedemptionRequest
import co.topl.shared.SessionNotFoundError
import co.topl.shared.StartSessionRequest
import co.topl.shared.utils.KeyGenerationUtils
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.staticcontent.resourceServiceBuilder
import quivr.models.KeyPair
import scopt.OParser

import java.util.concurrent.ConcurrentHashMap
import co.topl.shared.ToplNetworkIdentifiers
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.codecs.AddressCodecs
import co.topl.genus.services.TxoState
import co.topl.genus.services.Txo
import co.topl.brambl.models.Indices
import co.topl.brambl.models.LockAddress
import co.topl.brambl.utils.Encoding
import co.topl.brambl.models.LockId
import quivr.models.VerificationKey
import co.topl.shared.SyncWalletRequest

object Main
    extends IOApp
    with BridgeParamsDescriptor
    with WalletStateResource
    with RpcChannelResource {

  def apiServices(
      walletApi: WalletApi[IO],
      walletStateAlgebra: WalletStateAlgebra[IO],
      genusQueryAlgebra: GenusQueryAlgebra[IO],
      toplKeypair: KeyPair,
      sessionManager: SessionManagerAlgebra[IO],
      pegInWalletManager: BTCWalletAlgebra[IO],
      walletManager: BTCWalletAlgebra[IO],
      toplWalletAlgebra: ToplWalletAlgebra[IO],
      transactionAlgebra: TransactionAlgebra[IO],
      blockToRecover: Int,
      btcNetwork: BitcoinNetworkIdentifiers,
      toplNetwork: ToplNetworkIdentifiers
  ) = HttpRoutes.of[IO] {
    case req @ POST -> Root / "sync-wallet" =>
      implicit val syncWalletRequestDecoder
          : EntityDecoder[IO, SyncWalletRequest] =
        jsonOf[IO, SyncWalletRequest]
      (for {
        syncWalletRequest <- req.as[SyncWalletRequest]
        res <-
          if (syncWalletRequest.secret != "secret")
            BadRequest("Invalid secret")
          else
            sync(
              walletApi,
              walletStateAlgebra,
              genusQueryAlgebra,
              toplNetwork.networkId,
              "self",
              "default"
            ).flatMap(_ => Ok("Wallet Synced"))
      } yield res).handleErrorWith{ e =>
        e.printStackTrace()
        BadRequest("Error syncing wallet")
      }

    case req @ POST -> Root / "start-session" =>
      import StartSessionController._
      implicit val startSessionRequestDecoder
          : EntityDecoder[IO, StartSessionRequest] =
        jsonOf[IO, StartSessionRequest]
      for {
        x <- req.as[StartSessionRequest]
        res <- startSession(
          x,
          pegInWalletManager,
          sessionManager,
          blockToRecover,
          btcNetwork
        )
        resp <- res match {
          case Left(e: BridgeError) => BadRequest(e.asJson)
          case Right(value)         => Ok(value.asJson)
        }
      } yield resp
    case req @ POST -> Root / "confirm-deposit" =>
      implicit val confirmDepositRequestDecoder
          : EntityDecoder[IO, ConfirmDepositRequest] =
        jsonOf[IO, ConfirmDepositRequest]
      import ConfirmDepositController._
      for {
        x <- req.as[ConfirmDepositRequest]
        res <- confirmDeposit(
          toplKeypair,
          toplNetwork.networkId,
          x,
          toplWalletAlgebra,
          transactionAlgebra,
          10
        )
        resp <- res match {
          case Left(e: BridgeError) => BadRequest(e.asJson)
          case Right(value)         => Ok(value.asJson)
        }
      } yield resp
    case req @ POST -> Root / "confirm-redemption" =>
      import ConfirmRedemptionController._
      implicit val confirmRedemptionRequestDecoder
          : EntityDecoder[IO, ConfirmRedemptionRequest] =
        jsonOf[IO, ConfirmRedemptionRequest]
      for {
        x <- req.as[ConfirmRedemptionRequest]
        res <- confirmRedemption(
          x,
          pegInWalletManager,
          walletManager,
          sessionManager
        )
        resp <- res match {
          case Left(e: SessionNotFoundError) => NotFound(e.asJson)
          case Left(e: BridgeError)          => BadRequest(e.asJson)
          case Right(value)                  => Ok(value.asJson)
        }
      } yield resp
  }

  override def run(args: List[String]): IO[ExitCode] = {
    OParser.parse(parser, args, ToplBTCBridgeParamConfig()) match {
      case Some(config) =>
        runWithArgs(config)
      case None =>
        IO.consoleForIO.errorln("Invalid arguments") *>
          IO(ExitCode.Error)
    }
  }

  def sync(
      walletApi: WalletApi[IO],
      walletStateAlgebra: WalletStateAlgebra[IO],
      genusQueryAlgebra: GenusQueryAlgebra[IO],
      networkId: Int,
      fellowship: String,
      template: String
  ): IO[Unit] = {
    import cats.implicits._
    import TransactionBuilderApi.implicits._
    import co.topl.brambl.common.ContainsEvidence.Ops
    import co.topl.brambl.common.ContainsImmutable.instances._
    (for {
      // current indices
      someIndices <- walletStateAlgebra.getCurrentIndicesForFunds(
        fellowship,
        template,
        None
      )
      // current address
      someAddress <- walletStateAlgebra.getAddress(
        fellowship,
        template,
        someIndices.map(_.z)
      )
      // txos that are spent at current address
      txos <- someAddress
        .map(address =>
          genusQueryAlgebra
            .queryUtxo(
              AddressCodecs.decodeAddress(address).toOption.get,
              TxoState.SPENT
            )
        )
        .getOrElse(IO(Seq.empty[Txo]))
      _ <- IO(println(s"txos: $txos"))
    } yield
    // we have indices AND txos at current address are spent
    if (someIndices.isDefined && !txos.isEmpty) {
      // we need to update the wallet interaction with the next indices
      val indices = someIndices.map(idx => Indices(idx.x, idx.y, idx.z + 1)).get
      for {
        vks <- walletStateAlgebra.getEntityVks(
          fellowship,
          template
        )
        vksDerived <- vks.get
          .map(x =>
            walletApi.deriveChildVerificationKey(
              VerificationKey.parseFrom(
                Encoding.decodeFromBase58(x).toOption.get
              ),
              indices.z
            )
          )
          .sequence
        lock <- walletStateAlgebra.getLock(fellowship, template, indices.z)
        lockAddress = LockAddress(
          networkId,
          NetworkConstants.MAIN_LEDGER_ID,
          LockId(lock.get.getPredicate.sizedEvidence.digest.value)
        )
        _ <- walletStateAlgebra.updateWalletState(
          Encoding.encodeToBase58Check(
            lock.get.getPredicate.toByteArray
          ), // lockPredicate
          lockAddress.toBase58(), // lockAddress
          Some("ExtendedEd25519"),
          Some(Encoding.encodeToBase58(vksDerived.head.toByteArray)),
          indices
        )
        _ <- IO.println(s"Syncing wallet with indices: $indices")
      } yield txos
    } else {
      IO(txos)
    }).flatten
      .iterateUntil(x => x.isEmpty)
      .void
      // .map(_ => {
      //   println("Wallet Synced")
      // })
      // .handleError(e => {
      //   e.printStackTrace()
      // })
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
      pegInWalletManager <- Resource.make(
        BTCWalletImpl.make[IO](pegInKm)
      )(_ => IO.unit)
      walletManager <- Resource.make(
        BTCWalletImpl.make[IO](walletKm)
      )(_ => IO.unit)
      walletKeyApi = WalletKeyApi.make[IO]()
      walletApi = WalletApi.make[IO](walletKeyApi)
      walletStateAlgebra = WalletStateApi
        .make[IO](walletResource(params.toplWalletDb), walletApi)
      transactionBuilderApi = TransactionBuilderApi.make[IO](
        params.toplNetwork.networkId,
        NetworkConstants.MAIN_LEDGER_ID
      )
      genusQueryAlgebra = GenusQueryAlgebra.make[IO](
        channelResource(
          params.toplHost,
          params.toplPort,
          params.toplSecureConnection
        )
      )
      toplWalletImpl = ToplWalletImpl.make[IO](
        IO.asyncForIO,
        walletApi,
        walletStateAlgebra,
        transactionBuilderApi,
        genusQueryAlgebra
      )
      walletManagementUtils = new WalletManagementUtils(
        walletApi,
        walletKeyApi
      )
      transactionAlgebra = TransactionAlgebra.make[IO](
        walletApi,
        walletStateAlgebra,
        channelResource(
          params.toplHost,
          params.toplPort,
          params.toplSecureConnection
        )
      )
      keyPair <- Resource.make(
        walletManagementUtils.loadKeys(
          params.toplWalletSeedFile,
          params.toplWalletPassword
        )
      )(_ => IO.unit)
      app = {
        val sessionManager =
          SessionManagerImpl.make[IO](new ConcurrentHashMap())
        val router = Router.define(
          "/" -> apiServices(
            walletApi,
            walletStateAlgebra,
            genusQueryAlgebra,
            keyPair,
            sessionManager,
            pegInWalletManager,
            walletManager,
            toplWalletImpl,
            transactionAlgebra,
            params.blockToRecover,
            params.btcNetwork,
            params.toplNetwork
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
        e.printStackTrace()
        IO {
          Left(e.getMessage)
        }
      } >> IO.never

  }
}
