package co.topl.bridge.consensus.modules

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.GroupId
import co.topl.brambl.models.SeriesId
import co.topl.brambl.utils.Encoding
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.AssetToken
import co.topl.bridge.consensus.BTCWaitExpirationTime
import co.topl.bridge.consensus.BitcoinNetworkIdentifiers
import co.topl.bridge.consensus.Fellowship
import co.topl.bridge.consensus.Lvl
import co.topl.bridge.consensus.PeginSessionState.PeginSessionMintingTBTCConfirmation
import co.topl.bridge.consensus.PeginSessionState.PeginSessionStateMintingTBTC
import co.topl.bridge.consensus.PeginSessionState.PeginSessionStateSuccessfulPegin
import co.topl.bridge.consensus.PeginSessionState.PeginSessionStateTimeout
import co.topl.bridge.consensus.PeginSessionState.PeginSessionStateWaitingForBTC
import co.topl.bridge.consensus.PeginSessionState.PeginSessionWaitingForClaim
import co.topl.bridge.consensus.PeginSessionState.PeginSessionWaitingForClaimBTCConfirmation
import co.topl.bridge.consensus.PeginSessionState.PeginSessionWaitingForEscrowBTCConfirmation
import co.topl.bridge.consensus.PeginSessionState.PeginSessionWaitingForRedemption
import co.topl.bridge.consensus.PublicApiClientGrpc
import co.topl.bridge.consensus.ReplicaId
import co.topl.bridge.consensus.Template
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.controllers.StartSessionController
import co.topl.bridge.consensus.managers.BTCWalletAlgebra
import co.topl.bridge.consensus.managers.PeginSessionInfo
import co.topl.bridge.consensus.managers.SessionManagerAlgebra
import co.topl.bridge.consensus.monitor.WaitingBTCOps
import co.topl.bridge.consensus.monitor.WaitingForRedemptionOps
import co.topl.bridge.consensus.pbft.ConfirmDepositBTCEvt
import co.topl.bridge.consensus.pbft.ConfirmTBTCMintEvt
import co.topl.bridge.consensus.pbft.PBFTEvent
import co.topl.bridge.consensus.pbft.PBFTState
import co.topl.bridge.consensus.pbft.PBFTTransitionRelation
import co.topl.bridge.consensus.pbft.PSClaimingBTC
import co.topl.bridge.consensus.pbft.PSConfirmingBTCClaim
import co.topl.bridge.consensus.pbft.PSConfirmingBTCDeposit
import co.topl.bridge.consensus.pbft.PSConfirmingTBTCMint
import co.topl.bridge.consensus.pbft.PSMintingTBTC
import co.topl.bridge.consensus.pbft.PSWaitingForBTCDeposit
import co.topl.bridge.consensus.pbft.PSWaitingForRedemption
import co.topl.bridge.consensus.pbft.PostClaimTxEvt
import co.topl.bridge.consensus.pbft.PostDepositBTCEvt
import co.topl.bridge.consensus.pbft.PostRedemptionTxEvt
import co.topl.bridge.consensus.pbft.PostTBTCMintEvt
import co.topl.bridge.consensus.pbft.UndoClaimTxEvt
import co.topl.bridge.consensus.pbft.UndoDepositBTCEvt
import co.topl.bridge.consensus.pbft.UndoTBTCMintEvt
import co.topl.bridge.consensus.service.Empty
import co.topl.bridge.consensus.service.InvalidInputRes
import co.topl.bridge.consensus.service.MintingStatusOperation
import co.topl.bridge.consensus.service.MintingStatusRes
import co.topl.bridge.consensus.service.SessionNotFoundRes
import co.topl.bridge.consensus.service.StartSessionOperation
import co.topl.bridge.consensus.service.StartSessionRes
import co.topl.bridge.consensus.service.StateMachineReply.Result
import co.topl.bridge.consensus.service.StateMachineRequest
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.ConfirmClaimTx
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.ConfirmDepositBTC
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.ConfirmTBTCMint
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.MintingStatus
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.PostClaimTx
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.PostDepositBTC
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.PostRedemptionTx
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.PostTBTCMint
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.StartSession
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.TimeoutDepositBTC
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.TimeoutTBTCMint
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.UndoClaimTx
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.UndoDepositBTC
import co.topl.bridge.consensus.service.StateMachineRequest.Operation.UndoTBTCMint
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.bridge.consensus.utils.MiscUtils
import co.topl.shared.BridgeError
import co.topl.shared.ClientId
import co.topl.shared.ReplicaCount
import io.grpc.ManagedChannel
import io.grpc.Metadata
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger
import quivr.models.KeyPair
import scodec.bits.ByteVector

import java.security.PublicKey
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

trait ApiServicesModule {

  def grpcServices(
      idReplicaClientMap: Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
      lastReplyMap: ConcurrentHashMap[(ClientId, Long), Result],
      sessionState: ConcurrentHashMap[String, PBFTState],
      publicApiClientGrpcMap: Map[
        ClientId,
        (PublicApiClientGrpc[IO], PublicKey)
      ],
      sessionManager: SessionManagerAlgebra[IO],
      bridgeWalletManager: BTCWalletAlgebra[IO],
      btcNetwork: BitcoinNetworkIdentifiers,
      currentBTCHeightRef: Ref[IO, Int],
      currentView: Ref[IO, Long],
      currentToplHeight: Ref[IO, Long]
  )(implicit
      toplKeypair: KeyPair,
      bitcoindInstance: BitcoindRpcClient,
      channelResource: Resource[IO, ManagedChannel],
      pegInWalletManager: BTCWalletAlgebra[IO],
      utxoAlgebra: GenusQueryAlgebra[IO],
      defaultMintingFee: Lvl,
      defaultFeePerByte: CurrencyUnit,
      replicaId: ReplicaId,
      replicaCount: ReplicaCount,
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[IO],
      templateStorageAlgebra: TemplateStorageAlgebra[IO],
      tba: TransactionBuilderApi[IO],
      walletApi: WalletApi[IO],
      wsa: WalletStateAlgebra[IO],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      defaultFromFellowship: Fellowship,
      defaultFromTemplate: Template,
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId,
      logger: Logger[IO]
  ) = StateMachineServiceFs2Grpc.bindServiceResource(
    serviceImpl = new StateMachineServiceFs2Grpc[IO, Metadata] {
      // log4cats syntax
      import org.typelevel.log4cats.syntax._
      import WaitingBTCOps._
      import WaitingForRedemptionOps._

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

      private def executeStateMachine(
          sessionId: String,
          pbftEvent: PBFTEvent
      ) = {
        for {
          currentState <- Option(sessionState.get(sessionId))
          newState <- PBFTTransitionRelation.handlePBFTEvent(
            currentState,
            pbftEvent
          )
          _ <- Option(
            sessionState.put(sessionId, newState)
          )
        } yield newState // update the state
      }

      private def startSession(
          clientNumber: Int,
          timestamp: Long,
          sc: StartSessionOperation
      ) = {
        import StartSessionController._
        for {
          _ <-
            info"Received start session request from client ${clientNumber}"
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
          currentBTCHeight <- currentBTCHeightRef.get
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
              IO(
                sessionState.put(
                  sessionId,
                  PSWaitingForBTCDeposit(
                    height = currentBTCHeight,
                    currentWalletIdx = sessionInfo.btcPeginCurrentWalletIdx,
                    scriptAsm = sessionInfo.scriptAsm,
                    escrowAddress = sessionInfo.escrowAddress,
                    redeemAddress = sessionInfo.redeemAddress,
                    claimAddress = sessionInfo.claimAddress
                  )
                )
              ) >>
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

      private def toEvt(op: StateMachineRequest.Operation) = {
        op match {
          case StateMachineRequest.Operation.Empty =>
            throw new Exception("Invalid operation")
          case MintingStatus(_) =>
            throw new Exception("Invalid operation")
          case StartSession(_) =>
            throw new Exception("Invalid operation")
          case TimeoutDepositBTC(_) =>
            throw new Exception("Invalid operation")
          case PostDepositBTC(value) =>
            PostDepositBTCEvt(
              sessionId = value.sessionId,
              height = value.height,
              txId = value.txId,
              vout = value.vout,
              amount = Satoshis.fromBytes(ByteVector(value.amount.toByteArray))
            )
          case UndoDepositBTC(value) =>
            UndoDepositBTCEvt(
              sessionId = value.sessionId
            )
          case ConfirmDepositBTC(value) =>
            ConfirmDepositBTCEvt(
              sessionId = value.sessionId,
              height = value.height
            )
          case PostTBTCMint(value) =>
            import co.topl.brambl.syntax._
            PostTBTCMintEvt(
              sessionId = value.sessionId,
              height = value.height,
              utxoTxId = value.utxoTxId,
              utxoIdx = value.utxoIndex,
              amount = AssetToken(
                Encoding.encodeToBase58(groupIdIdentifier.value.toByteArray),
                Encoding.encodeToBase58(seriesIdIdentifier.value.toByteArray),
                BigInt(value.amount.toByteArray())
              )
            )
          case TimeoutTBTCMint(_) =>
            throw new Exception("Invalid operation")
          case UndoTBTCMint(value) =>
            UndoTBTCMintEvt(
              sessionId = value.sessionId
            )
          case ConfirmTBTCMint(value) =>
            ConfirmTBTCMintEvt(
              sessionId = value.sessionId,
              height = value.height
            )
          case PostRedemptionTx(value) =>
            import co.topl.brambl.syntax._
            PostRedemptionTxEvt(
              sessionId = value.sessionId,
              secret = value.secret,
              height = value.height,
              utxoTxId = value.utxoTxId,
              utxoIdx = value.utxoIndex,
              amount = AssetToken(
                Encoding.encodeToBase58(groupIdIdentifier.value.toByteArray),
                Encoding.encodeToBase58(seriesIdIdentifier.value.toByteArray),
                BigInt(value.amount.toByteArray())
              )
            )
          case PostClaimTx(value) =>
            PostClaimTxEvt(
              sessionId = value.sessionId,
              height = value.height,
              txId = value.txId,
              vout = value.vout
            )
          case UndoClaimTx(value) =>
            UndoClaimTxEvt(
              sessionId = value.sessionId
            )
          case ConfirmClaimTx(_) =>
            throw new Exception("Invalid operation")
        }

      }

      private def pbftStateToPeginSessionState(pbftState: PBFTState) =
        pbftState match {
          case _: PSWaitingForBTCDeposit => PeginSessionStateWaitingForBTC
          case _: PSConfirmingBTCDeposit =>
            PeginSessionWaitingForEscrowBTCConfirmation
          case _: PSMintingTBTC          => PeginSessionStateMintingTBTC
          case _: PSWaitingForRedemption => PeginSessionWaitingForRedemption
          case _: PSConfirmingTBTCMint   => PeginSessionMintingTBTCConfirmation
          case _: PSClaimingBTC          => PeginSessionWaitingForClaim
          case _: PSConfirmingBTCClaim =>
            PeginSessionWaitingForClaimBTCConfirmation
        }

      private def standardResponse(
          clientNumber: Int,
          timestamp: Long,
          sessionId: String,
          value: StateMachineRequest.Operation
      ) = {
        for {
          viewNumber <- currentView.get
          newState <- IO(
            executeStateMachine(
              sessionId,
              toEvt(value)
            )
          )
          someSessionInfo <- sessionManager.updateSession(
            sessionId,
            x =>
              newState
                .map(y =>
                  x.copy(
                    mintingBTCState = pbftStateToPeginSessionState(y)
                  )
                )
                .getOrElse(x)
          )
          _ <- publicApiClientGrpcMap(ClientId(clientNumber))._1
            .replyStartPegin(timestamp, viewNumber, Result.Empty)
        } yield someSessionInfo
      }

      private def sendResponse(
          clientNumber: Int,
          timestamp: Long
      ) = {
        for {
          viewNumber <- currentView.get
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
              _ <- debug"Request.clientNumber: ${request.clientNumber}"
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
                    case StartSession(sc) =>
                      startSession(request.clientNumber, request.timestamp, sc)
                    case PostDepositBTC(
                          value
                        ) => // FIXME: add checks before executing
                      standardResponse(
                        request.clientNumber,
                        request.timestamp,
                        value.sessionId,
                        request.operation
                      ) >> IO.pure(Result.Empty)
                    case TimeoutDepositBTC(
                          value
                        ) => // FIXME: add checks before executing
                      IO(sessionState.remove(value.sessionId)) >>
                        sessionManager.removeSession(
                          value.sessionId,
                          PeginSessionStateTimeout
                        ) >> sendResponse(
                          request.clientNumber,
                          request.timestamp
                        ) // FIXME: this is just a change of state at db level
                    case UndoDepositBTC(
                          value
                        ) => // FIXME: add checks before executing
                      standardResponse(
                        request.clientNumber,
                        request.timestamp,
                        value.sessionId,
                        request.operation
                      ) >> IO.pure(Result.Empty)
                    case ConfirmDepositBTC(
                          value
                        ) =>
                      import co.topl.brambl.syntax._
                      for {
                        _ <- trace"Deposit has been confirmed"
                        someSessionInfo <- standardResponse(
                          request.clientNumber,
                          request.timestamp,
                          value.sessionId,
                          request.operation
                        )
                        _ <- trace"Minting: ${BigInt(value.amount.toByteArray())}"
                        _ <- someSessionInfo
                          .flatMap(sessionInfo =>
                            MiscUtils.sessionInfoPeginPrism
                              .getOption(sessionInfo)
                              .map(peginSessionInfo =>
                                startMintingProcess[IO](
                                  defaultFromFellowship,
                                  defaultFromTemplate,
                                  peginSessionInfo.redeemAddress,
                                  BigInt(value.amount.toByteArray())
                                )
                              )
                          )
                          .getOrElse(IO.unit)
                      } yield Result.Empty
                    case PostTBTCMint(
                          value
                        ) => // FIXME: add checks before executing
                      warn"Minting has succeeded" >> standardResponse(
                        request.clientNumber,
                        request.timestamp,
                        value.sessionId,
                        request.operation
                      ) >> IO.pure(Result.Empty)
                    case TimeoutTBTCMint(
                          value
                        ) => // FIXME: Add checks before executing
                      IO(sessionState.remove(value.sessionId)) >>
                        sessionManager.removeSession(
                          value.sessionId,
                          PeginSessionStateTimeout
                        ) >> sendResponse(
                          request.clientNumber,
                          request.timestamp
                        ) // FIXME: this is just a change of state at db level
                    case UndoTBTCMint(
                          value
                        ) => // FIXME: Add checks before executing
                      warn"Received undo mint" >> standardResponse(
                        request.clientNumber,
                        request.timestamp,
                        value.sessionId,
                        request.operation
                      ) >> IO.pure(Result.Empty)
                    case ConfirmTBTCMint(
                          value
                        ) => // FIXME: Add checks before executing
                      standardResponse(
                        request.clientNumber,
                        request.timestamp,
                        value.sessionId,
                        request.operation
                      ) >> IO.pure(Result.Empty)
                    case PostRedemptionTx(
                          value
                        ) => // FIXME: Add checks before executing
                      for {
                        someSessionInfo <- standardResponse(
                          request.clientNumber,
                          request.timestamp,
                          value.sessionId,
                          request.operation
                        )
                        _ <- (for {
                          sessionInfo <- someSessionInfo
                          peginSessionInfo <- MiscUtils.sessionInfoPeginPrism
                            .getOption(sessionInfo)
                        } yield startClaimingProcess[IO](
                          value.secret,
                          peginSessionInfo.claimAddress,
                          peginSessionInfo.btcBridgeCurrentWalletIdx,
                          value.txId,
                          value.vout,
                          peginSessionInfo.scriptAsm, // scriptAsm,
                          Satoshis
                            .fromLong(
                              BigInt(value.amount.toByteArray()).toLong
                            )
                        )).getOrElse(IO.unit)
                      } yield Result.Empty
                    case PostClaimTx(value) =>
                      warn"Claimed TX" >> standardResponse(
                        request.clientNumber,
                        request.timestamp,
                        value.sessionId,
                        request.operation
                      ) >> IO.pure(Result.Empty)
                    case UndoClaimTx(value) =>
                      warn"Undo claim received" >> standardResponse(
                        request.clientNumber,
                        request.timestamp,
                        value.sessionId,
                        request.operation
                      ) >> IO.pure(Result.Empty)
                    case ConfirmClaimTx(value) =>
                      warn"Confirmation received" >> IO(
                        sessionState.remove(value.sessionId)
                      ) >> warn"Removed session from map" >>
                        sessionManager.removeSession(
                          value.sessionId,
                          PeginSessionStateSuccessfulPegin
                        ) >> warn"Updated session ${value.sessionId} on DB" >> sendResponse(
                          request.clientNumber,
                          request.timestamp
                        ) // FIXME: this is just a change of state at db level
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
