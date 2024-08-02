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
import co.topl.bridge.consensus.PeginSessionState.PeginSessionWaitingForEscrowBTCConfirmation
import co.topl.bridge.consensus.PublicApiClientGrpc
import co.topl.bridge.consensus.ReplicaId
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.controllers.StartSessionController
import co.topl.bridge.consensus.managers.BTCWalletAlgebra
import co.topl.bridge.consensus.managers.PeginSessionInfo
import co.topl.bridge.consensus.managers.SessionManagerAlgebra
import co.topl.bridge.consensus.service.Empty
import co.topl.bridge.consensus.service.InvalidInputRes
import co.topl.bridge.consensus.service.MintingStatusOperation
import co.topl.bridge.consensus.service.MintingStatusRes
import co.topl.bridge.consensus.service.PostDepositBTCOperation
import co.topl.bridge.consensus.service.SessionNotFoundRes
import co.topl.bridge.consensus.service.StartSessionOperation
import co.topl.bridge.consensus.service.StartSessionRes
import co.topl.bridge.consensus.service.StateMachineReply.Result
import co.topl.bridge.consensus.service.StateMachineRequest
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.MintingStatus
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.PostDepositBTC
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.StartSession
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.shared.BridgeError
import co.topl.shared.ReplicaCount
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import quivr.models.KeyPair

import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

trait ApiServicesModule {

  def grpcServices(
      idReplicaClientMap: Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
      lastReplyMap: ConcurrentHashMap[(ClientId, Long), Result],
      publicApiClientGrpcMap: Map[
        ClientId,
        (PublicApiClientGrpc[IO], PublicKey)
      ],
      toplKeypair: KeyPair,
      sessionManager: SessionManagerAlgebra[IO],
      pegInWalletManager: BTCWalletAlgebra[IO],
      bridgeWalletManager: BTCWalletAlgebra[IO],
      btcNetwork: BitcoinNetworkIdentifiers,
      currentView: Ref[IO, Long],
      currentToplHeight: Ref[IO, Long]
  )(implicit
      replicaId: ReplicaId,
      replicaCount: ReplicaCount,
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
      import org.typelevel.log4cats.syntax._

      private def mintingStatus(
          clientNumber: Int,
          timestamp: Long,
          value: MintingStatusOperation
      ) =
        for {
          session <- sessionManager.getSession(value.sessionId)
          viewNumber <- currentView.get
          somePegin <- session match {
            case Some(p: PeginSessionInfo) => IO.pure(Option(p))
            case None                      => IO.pure(None)
            case _ =>
              IO.raiseError(new Exception("Invalid session type"))
          }
          resp: Result = somePegin match {
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
          _ <- publicApiClientGrpcMap(ClientId(clientNumber))._1
            .replyStartPegin(timestamp, viewNumber, resp)
        } yield resp

      private def startSession(
          clientNumber: Int,
          timestamp: Long,
          sc: StartSessionOperation
      ) = {
        import StartSessionController._
        for {
          _ <-
            warn"Received start session request from client ${clientNumber}"
          sessionId <- IO(
            sc.sessionId.getOrElse(UUID.randomUUID().toString)
          )
          res <- startPeginSession(
            sessionId,
            sc,
            pegInWalletManager,
            bridgeWalletManager,
            toplKeypair,
            currentToplHeight,
            btcNetwork
          )
          viewNumber <- currentView.get
          resp <- res match {
            case Left(e: BridgeError) =>
              IO(
                Result.InvalidInput(
                  InvalidInputRes(
                    e.error
                  )
                )
              )
            case Right((sessionInfo, response)) =>
              sessionManager.createNewSession(sessionId, sessionInfo) >>
                IO(
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
                )

          }
          _ <- publicApiClientGrpcMap(ClientId(clientNumber))._1
            .replyStartPegin(timestamp, viewNumber, resp)
        } yield resp
      }

      private def postDepositBTC(
          clientNumber: Int,
          timestamp: Long,
          value: PostDepositBTCOperation
      ) = {
        for {
          viewNumber <- currentView.get
          _ <- sessionManager.updateSession(
            value.sessionId,
            _.copy(
              mintingBTCState = PeginSessionWaitingForEscrowBTCConfirmation
            )
          )
          _ <- publicApiClientGrpcMap(ClientId(clientNumber))._1
            .replyStartPegin(timestamp, viewNumber, Result.Empty)
        } yield Result.Empty
      }

      def executeRequest(
          request: co.topl.bridge.consensus.service.StateMachineRequest,
          ctx: Metadata
      ): IO[Empty] = {
        Option(
          lastReplyMap.get((ClientId(request.clientNumber), request.timestamp))
        ) match {
          case Some(result) => // we had a cached response
            for {
              viewNumber <- currentView.get
              _ <- publicApiClientGrpcMap(ClientId(request.clientNumber))._1
                .replyStartPegin(request.timestamp, viewNumber, result)
            } yield Empty()
          case None =>
            for {
              currentView <- currentView.get
              currentPrimary = currentView % replicaCount.value
              _ <-
                if (currentPrimary != replicaId.id)
                  // we are not the primary, forward the request
                  idReplicaClientMap(
                    replicaId.id
                  ).executeRequest(request, ctx)
                else {
                  // we are the primary, process the request
                  (request.operation match {
                    case StateMachineRequest.Operation.Empty =>
                      warn"Received empty message" >> IO.pure(Result.Empty)
                    case MintingStatus(value) =>
                      mintingStatus(
                        request.clientNumber,
                        request.timestamp,
                        value
                      )
                    case PostDepositBTC(value) =>
                      IO.pure(Result.Empty)
                    case StartSession(sc) =>
                      startSession(request.clientNumber, request.timestamp, sc)
                  }).flatMap(x =>
                    IO(
                      lastReplyMap.put(
                        (ClientId(request.clientNumber), request.timestamp),
                        x
                      )
                    )
                  ) >> IO.pure(Empty())
                }
            } yield Empty()

        }
      }
    }
  )

}
