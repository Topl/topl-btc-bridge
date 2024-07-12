package co.topl.bridge.consensus.modules

import cats.effect.IO
import cats.effect.kernel.Ref
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.BTCWaitExpirationTime
import co.topl.bridge.consensus.BitcoinNetworkIdentifiers
import co.topl.bridge.consensus.ClientId
import co.topl.bridge.consensus.PublicApiClientGrpc
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.controllers.StartSessionController
import co.topl.bridge.consensus.managers.BTCWalletAlgebra
import co.topl.bridge.consensus.managers.PeginSessionInfo
import co.topl.bridge.consensus.managers.SessionManagerAlgebra
import co.topl.bridge.consensus.service.Empty
import co.topl.bridge.consensus.service.InvalidInputRes
import co.topl.bridge.consensus.service.MintingStatusRes
import co.topl.bridge.consensus.service.SessionNotFoundRes
import co.topl.bridge.consensus.service.StartSessionRes
import co.topl.bridge.consensus.service.StateMachineReply.Result
import co.topl.bridge.consensus.service.StateMachineRequest
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.MintingStatus
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.StartSession
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.shared.BridgeError
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import quivr.models.KeyPair

import java.security.PublicKey

trait ApiServicesModule {

  def grpcServices(
      publicApiClientGrpcMap: Map[
        ClientId,
        (PublicApiClientGrpc[IO], PublicKey)
      ],
      toplKeypair: KeyPair,
      sessionManager: SessionManagerAlgebra[IO],
      pegInWalletManager: BTCWalletAlgebra[IO],
      bridgeWalletManager: BTCWalletAlgebra[IO],
      btcNetwork: BitcoinNetworkIdentifiers,
      currentToplHeight: Ref[IO, Long]
  )(implicit
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[IO],
      templateStorageAlgebra: TemplateStorageAlgebra[IO],
      tba: TransactionBuilderApi[IO],
      walletApi: WalletApi[IO],
      wsa: WalletStateAlgebra[IO],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      logger: Logger[IO]
  ) = StateMachineServiceFs2Grpc.bindServiceResource(
    serviceImpl = new StateMachineServiceFs2Grpc[IO, Metadata] {
      // log4cats syntax

      def executeRequest(
          request: co.topl.bridge.consensus.service.StateMachineRequest,
          ctx: Metadata
      ): IO[Empty] = {
        request.operation match {
          case StateMachineRequest.Operation.Empty => IO.pure(Empty())
          case MintingStatus(value) =>
            for {
              session <- sessionManager.getSession(value.sessionId)
              somePegin <- session match {
                case Some(p: PeginSessionInfo) => IO.pure(Option(p))
                case None                      => IO.pure(None)
                case _ => IO.raiseError(new Exception("Invalid session type"))
              }
              resp = somePegin match {
                case Some(pegin) =>
                  Result.MintingStatus(
                    MintingStatusRes(
                      sessionId = value.sessionId,
                      mintingStatus = pegin.mintingBTCState.toString(),
                      address = pegin.redeemAddress,
                      redeemScript =
                        s""""threshold(1, sha256(${pegin.sha256}) and height(${pegin.minHeight}, ${pegin.maxHeight}))"""
                    )
                  )
                case None =>
                  Result.SessionNotFound(
                    SessionNotFoundRes(
                      value.sessionId
                    )
                  )
              }
              _ <- publicApiClientGrpcMap(ClientId(request.clientNumber))._1
                .replyStartPegin(request.timestamp, resp)
            } yield Empty()
          case StartSession(sc) =>
            import StartSessionController._
            for {
              res <- startPeginSession(
                sc,
                pegInWalletManager,
                bridgeWalletManager,
                sessionManager,
                toplKeypair,
                currentToplHeight,
                btcNetwork
              )
              resp = res match {
                case Left(e: BridgeError) =>
                  Result.InvalidInput(
                    InvalidInputRes(
                      e.error
                    )
                  )
                case Right(response) =>
                  Result.StartSession(
                    StartSessionRes(
                      sessionId = response.sessionID,
                      script = response.script,
                      escrowAddress = response.escrowAddress,
                      descriptor = response.descriptor,
                      minHeight = response.minHeight,
                      maxHeight = response.maxHeight
                    )
                  )

              }
              _ <- publicApiClientGrpcMap(ClientId(request.clientNumber))._1
                .replyStartPegin(request.timestamp, resp)
            } yield Empty()
        }
      }
    }
  )

}
