package co.topl.bridge

import cats.effect.IO
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.codecs.AddressCodecs
import co.topl.brambl.constants.NetworkConstants
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.Indices
import co.topl.brambl.models.LockAddress
import co.topl.brambl.models.LockId
import co.topl.brambl.utils.Encoding
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.controllers.ConfirmDepositController
import co.topl.bridge.controllers.ConfirmRedemptionController
import co.topl.bridge.controllers.StartSessionController
import co.topl.bridge.managers.BTCWalletAlgebra
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.bridge.managers.TransactionAlgebra
import co.topl.genus.services.Txo
import co.topl.genus.services.TxoState
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.BridgeContants
import co.topl.shared.BridgeError
import co.topl.shared.ConfirmDepositRequest
import co.topl.shared.ConfirmRedemptionRequest
import co.topl.shared.SessionNotFoundError
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
import quivr.models.KeyPair
import quivr.models.VerificationKey
import cats.effect.kernel.Ref
import org.typelevel.log4cats.Logger
import co.topl.shared.MintingStatusRequest
import co.topl.shared.MintingStatusResponse
import co.topl.bridge.managers.PeginSessionInfo

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
      toplNetwork: ToplNetworkIdentifiers,
      transactionBuilderApi: TransactionBuilderApi[IO],
      currentState: Ref[IO, SystemGlobalState]
  )(implicit logger: Logger[IO]) = {
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

        for {
          x <- req.as[StartPeginSessionRequest]
          res <- startPeginSession(
            x,
            pegInWalletManager,
            sessionManager,
            blockToRecover,
            toplKeypair,
            toplWalletAlgebra,
            btcNetwork
          )
          resp <- res match {
            case Left(e: BridgeError) => BadRequest(e.asJson)
            case Right(value)         => Ok(value.asJson)
          }
        } yield resp
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
            toplWalletAlgebra,
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
          _ <- IO.println("Session ID: " + x.sessionID)
          session <- sessionManager.getSession(x.sessionID)
          pegin <- session match {
            case p: PeginSessionInfo => IO.pure(p)
            case _ => IO.raiseError(new Exception("Invalid session type"))
          }
          resp <- Ok(
            MintingStatusResponse(
              pegin.mintingBTCState.toString(),
              pegin.redeemAddress,
              pegin.toplBridgePKey,
              s"threshold(1, sign(0) or sha256(${pegin.sha256}))",
            ).asJson
          )
          _ <- IO.println("Minting status response: " + resp)
        } yield resp
      case req @ POST -> Root / BridgeContants.CONFIRM_DEPOSIT_BTC_PATH =>
        implicit val confirmDepositRequestDecoder
            : EntityDecoder[IO, ConfirmDepositRequest] =
          jsonOf[IO, ConfirmDepositRequest]
        for {
          x <- req.as[ConfirmDepositRequest]
          confirmDepositController = new ConfirmDepositController[IO](
            walletStateAlgebra,
            transactionBuilderApi
          )(IO.asyncForIO, logger)
          res <- confirmDepositController.confirmDeposit(
            toplKeypair,
            x,
            toplWalletAlgebra,
            transactionAlgebra,
            genusQueryAlgebra,
            10,
            sessionManager
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
  }

}
