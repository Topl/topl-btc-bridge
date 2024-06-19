package co.topl.bridge.modules

import cats.effect.IO
import cats.effect.kernel.Ref
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.codecs.AddressCodecs
import co.topl.brambl.constants.NetworkConstants
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.Indices
import co.topl.brambl.models.LockAddress
import co.topl.brambl.models.LockId
import co.topl.brambl.utils.Encoding
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.SystemGlobalState
import co.topl.bridge.controllers.StartSessionController
import co.topl.bridge.managers.BTCWalletAlgebra
import co.topl.bridge.managers.PeginSessionInfo
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.genus.services.Txo
import co.topl.genus.services.TxoState
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.BridgeContants
import co.topl.shared.BridgeError
import co.topl.shared.MintingStatusRequest
import co.topl.shared.MintingStatusResponse
import co.topl.shared.StartPeginSessionRequest
import co.topl.shared.StartPegoutSessionRequest
import co.topl.shared.SyncWalletRequest
import co.topl.shared.ToplNetworkIdentifiers
import io.circe.Encoder
import io.circe.Json
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import org.http4s.server.middleware.CORS
import quivr.models.KeyPair
import quivr.models.VerificationKey
import co.topl.bridge.BTCWaitExpirationTime
import co.topl.bridge.ToplWaitExpirationTime

trait ApiServicesModule {

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
          Some(
            Encoding.encodeToBase58(vksDerived.head.toByteArray)
          ), // TODO: we assume here only one party, fix this later
          indices
        )
      } yield txos
    } else {
      IO(txos)
    }).flatten
      .iterateUntil(x => x.isEmpty)
      .void
  }

  def apiServices(
      walletStateAlgebra: WalletStateAlgebra[IO],
      toplKeypair: KeyPair,
      sessionManager: SessionManagerAlgebra[IO],
      pegInWalletManager: BTCWalletAlgebra[IO],
      bridgeWalletManager: BTCWalletAlgebra[IO],
      btcNetwork: BitcoinNetworkIdentifiers,
      toplNetwork: ToplNetworkIdentifiers,
      currentToplHeight: Ref[IO, Long],
      currentState: Ref[IO, SystemGlobalState]
  )(implicit
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[IO],
      templateStorageAlgebra: TemplateStorageAlgebra[IO],
      tba: TransactionBuilderApi[IO],
      walletApi: WalletApi[IO],
      wsa: WalletStateAlgebra[IO],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      genusQueryAlgebra: GenusQueryAlgebra[IO]
  ) = {
    import io.circe.syntax._
    implicit val bridgeErrorEncoder: Encoder[BridgeError] =
      new Encoder[BridgeError] {

        override def apply(a: BridgeError): Json = Json.obj(
          ("error", Json.fromString(a.error))
        )

      }
    HttpRoutes.of[IO] {
      case GET -> Root / "system-state" =>
        for {
          state <- currentState.get
          resp <- Ok(state.asJson)
        } yield resp
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
        } yield res).handleErrorWith { e =>
          e.printStackTrace()
          BadRequest("Error syncing wallet")
        }
      case req @ POST -> Root / BridgeContants.START_PEGIN_SESSION_PATH =>
        import StartSessionController._
        implicit val startSessionRequestDecoder
            : EntityDecoder[IO, StartPeginSessionRequest] =
          jsonOf[IO, StartPeginSessionRequest]

        (for {
          x <- req.as[StartPeginSessionRequest]
          res <- startPeginSession(
            x,
            pegInWalletManager,
            bridgeWalletManager,
            sessionManager,
            toplKeypair,
            currentToplHeight,
            btcNetwork
          )
          resp <- res match {
            case Left(e: BridgeError) => BadRequest(e.asJson)
            case Right(value)         => Ok(value.asJson)
          }
        } yield resp).handleErrorWith { case e =>
          e.printStackTrace()
          BadRequest("Error starting pegin session")
        }
      case req @ POST -> Root / BridgeContants.START_PEGOUT_SESSION_PATH =>
        import StartSessionController._
        implicit val startSessionRequestDecoder
            : EntityDecoder[IO, StartPegoutSessionRequest] =
          jsonOf[IO, StartPegoutSessionRequest]
        (for {
          x <- req.as[StartPegoutSessionRequest]
          res <- startPegoutSession(
            x,
            toplNetwork,
            toplKeypair,
            sessionManager,
            1000
          )
          resp <- res match {
            case Left(e: BridgeError) => BadRequest(e.asJson)
            case Right(value)         => Ok(value.asJson)
          }
        } yield resp).handleErrorWith { case e =>
          e.printStackTrace()
          BadRequest("Error starting pegout session")
        }
      case req @ POST -> Root / BridgeContants.TOPL_MINTING_STATUS =>
        implicit val mintingRequestDecoder
            : EntityDecoder[IO, MintingStatusRequest] =
          jsonOf[IO, MintingStatusRequest]
        for {
          x <- req.as[MintingStatusRequest]
          session <- sessionManager.getSession(x.sessionID)
          somePegin <- session match {
            case Some(p: PeginSessionInfo) => IO.pure(Option(p))
            case None                      => IO.pure(None)
            case _ => IO.raiseError(new Exception("Invalid session type"))
          }
          resp <- somePegin match {
            case Some(pegin) =>
              Ok(
                MintingStatusResponse(
                  pegin.mintingBTCState.toString(),
                  pegin.redeemAddress,
                  s""""threshold(1, sha256(${pegin.sha256}) and height(${pegin.minHeight}, ${pegin.maxHeight}))"""
                ).asJson
              )
            case None =>
              NotFound("Session not found")
          }
        } yield resp
    }
  }

}
