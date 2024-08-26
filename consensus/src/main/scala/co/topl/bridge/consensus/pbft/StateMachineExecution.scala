package co.topl.bridge.consensus.pbft

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
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
import co.topl.bridge.consensus.BridgeWalletManager
import co.topl.bridge.consensus.CurrentBTCHeight
import co.topl.bridge.consensus.CurrentToplHeight
import co.topl.bridge.consensus.CurrentView
import co.topl.bridge.consensus.Fellowship
import co.topl.bridge.consensus.LastReplyMap
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
import co.topl.bridge.consensus.PeginWalletManager
import co.topl.bridge.consensus.PublicApiClientGrpcMap
import co.topl.bridge.consensus.SessionState
import co.topl.bridge.consensus.Template
import co.topl.bridge.consensus.ToplKeypair
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.controllers.StartSessionController
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
import co.topl.bridge.consensus.service.InvalidInputRes
import co.topl.bridge.consensus.service.MintingStatusRes
import co.topl.bridge.consensus.service.SessionNotFoundRes
import co.topl.bridge.consensus.service.StartSessionRes
import co.topl.bridge.consensus.service.StateMachineReply.Result
import co.topl.bridge.consensus.utils.MiscUtils
import co.topl.bridge.shared.MintingStatusOperation
import co.topl.bridge.shared.StartSessionOperation
import co.topl.bridge.shared.StateMachineRequest
import co.topl.bridge.shared.StateMachineRequest.Operation.ConfirmClaimTx
import co.topl.bridge.shared.StateMachineRequest.Operation.ConfirmDepositBTC
import co.topl.bridge.shared.StateMachineRequest.Operation.ConfirmTBTCMint
import co.topl.bridge.shared.StateMachineRequest.Operation.MintingStatus
import co.topl.bridge.shared.StateMachineRequest.Operation.PostClaimTx
import co.topl.bridge.shared.StateMachineRequest.Operation.PostDepositBTC
import co.topl.bridge.shared.StateMachineRequest.Operation.PostRedemptionTx
import co.topl.bridge.shared.StateMachineRequest.Operation.PostTBTCMint
import co.topl.bridge.shared.StateMachineRequest.Operation.StartSession
import co.topl.bridge.shared.StateMachineRequest.Operation.TimeoutDepositBTC
import co.topl.bridge.shared.StateMachineRequest.Operation.TimeoutTBTCMint
import co.topl.bridge.shared.StateMachineRequest.Operation.UndoClaimTx
import co.topl.bridge.shared.StateMachineRequest.Operation.UndoDepositBTC
import co.topl.bridge.shared.StateMachineRequest.Operation.UndoTBTCMint
import co.topl.shared.BridgeError
import co.topl.shared.ClientId
import io.grpc.ManagedChannel
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector

import java.util.UUID

object StateMachineExecution {


  import org.typelevel.log4cats.syntax._
  import cats.implicits._
  import WaitingBTCOps._
  import WaitingForRedemptionOps._

  private def startSession[F[_]: Async: Logger](
      clientNumber: Int,
      timestamp: Long,
      sc: StartSessionOperation
  )(implicit
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      sessionManager: SessionManagerAlgebra[F],
      toplKeypair: ToplKeypair,
      sessionState: SessionState,
      currentView: CurrentView[F],
      currentBTCHeightRef: CurrentBTCHeight[F],
      btcNetwork: BitcoinNetworkIdentifiers,
      pegInWalletManager: PeginWalletManager[F],
      bridgeWalletManager: BridgeWalletManager[F],
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
      templateStorageAlgebra: TemplateStorageAlgebra[F],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      tba: TransactionBuilderApi[F],
      currentToplHeight: CurrentToplHeight[F],
      walletApi: WalletApi[F],
      wsa: WalletStateAlgebra[F]
  ): F[Result] = {
    import StartSessionController._
    for {
      _ <-
        info"Received start session request from client ${clientNumber}"
      sessionId <- Sync[F].delay(
        sc.sessionId.getOrElse(UUID.randomUUID().toString)
      )
      res <- startPeginSession[F](
        sessionId,
        sc
      )
      viewNumber <- currentView.underlying.get
      currentBTCHeight <- currentBTCHeightRef.underlying.get
      resp <- res match {
        case Left(e: BridgeError) =>
          Sync[F].delay(
            Result.InvalidInput(
              InvalidInputRes(
                e.error
              )
            )
          )
        case Right((sessionInfo, response)) =>
          Sync[F].delay(
            sessionState.underlying.put(
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
          ) >> sessionManager
            .createNewSession(sessionId, sessionInfo) >> Sync[F].delay(
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
      _ <- publicApiClientGrpcMap
        .underlying(ClientId(clientNumber))
        ._1
        .replyStartPegin(timestamp, viewNumber, resp)
    } yield resp
  }

  private def mintingStatus[F[_]: Sync](
      clientNumber: Int,
      timestamp: Long,
      value: MintingStatusOperation
  )(implicit
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      currentView: CurrentView[F],
      sessionManager: SessionManagerAlgebra[F]
  ) =
    for {
      session <- sessionManager.getSession(value.sessionId)
      viewNumber <- currentView.underlying.get
      somePegin <- session match {
        case Some(p: PeginSessionInfo) => Sync[F].delay(Option(p))
        case None                      => Sync[F].delay(None)
        case _ =>
          Sync[F].raiseError(new Exception("Invalid session type"))
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
      _ <- publicApiClientGrpcMap
        .underlying(ClientId(clientNumber))
        ._1
        .replyStartPegin(timestamp, viewNumber, resp)
    } yield resp

  private def toEvt(op: StateMachineRequest.Operation)(implicit
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId
  ) = {
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

  private def executeStateMachine(
      sessionId: String,
      pbftEvent: PBFTEvent
  )(implicit
      sessionState: SessionState
  ) = {
    for {
      currentState <- Option(sessionState.underlying.get(sessionId))
      newState <- PBFTTransitionRelation.handlePBFTEvent(
        currentState,
        pbftEvent
      )
      _ <- Option(
        sessionState.underlying.put(sessionId, newState)
      )
    } yield newState // update the state
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

  private def standardResponse[F[_]: Sync](
      clientNumber: Int,
      timestamp: Long,
      sessionId: String,
      value: StateMachineRequest.Operation
  )(implicit
      currentView: CurrentView[F],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      sessionState: SessionState,
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId,
      sessionManager: SessionManagerAlgebra[F]
  ) = {
    for {
      viewNumber <- currentView.underlying.get
      newState <- Sync[F].delay(
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
      _ <- publicApiClientGrpcMap
        .underlying(ClientId(clientNumber))
        ._1
        .replyStartPegin(timestamp, viewNumber, Result.Empty)
    } yield someSessionInfo
  }

  private def sendResponse[F[_]: Sync](
      clientNumber: Int,
      timestamp: Long
  )(implicit
      currentView: CurrentView[F],
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F]
  ) = {
    for {
      viewNumber <- currentView.underlying.get
      _ <- publicApiClientGrpcMap
        .underlying(ClientId(clientNumber))
        ._1
        .replyStartPegin(timestamp, viewNumber, Result.Empty)
    } yield Result.Empty
  }

  def executeRequest[F[_]: Async: Logger](
      request: co.topl.bridge.shared.StateMachineRequest
  )(implicit
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      currentView: CurrentView[F],
      sessionManager: SessionManagerAlgebra[F],
      toplKeypair: ToplKeypair,
      sessionState: SessionState,
      currentBTCHeightRef: CurrentBTCHeight[F],
      btcNetwork: BitcoinNetworkIdentifiers,
      pegInWalletManager: PeginWalletManager[F],
      bridgeWalletManager: BridgeWalletManager[F],
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
      templateStorageAlgebra: TemplateStorageAlgebra[F],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcWaitExpirationTime: BTCWaitExpirationTime,
      tba: TransactionBuilderApi[F],
      currentToplHeight: CurrentToplHeight[F],
      walletApi: WalletApi[F],
      wsa: WalletStateAlgebra[F],
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId,
      utxoAlgebra: GenusQueryAlgebra[F],
      channelResource: Resource[F, ManagedChannel],
      defaultMintingFee: Lvl,
      lastReplyMap: LastReplyMap,
      defaultFromFellowship: Fellowship,
      defaultFromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      defaultFeePerByte: CurrencyUnit
  ) =
    (request.operation match {
      case StateMachineRequest.Operation.Empty =>
        warn"Received empty message" >> Sync[F].delay(Result.Empty)
      case MintingStatus(value) =>
        mintingStatus(
          request.clientNumber,
          request.timestamp,
          value
        )
      case StartSession(sc) =>
        startSession[F](
          request.clientNumber,
          request.timestamp,
          sc
        )
      case PostDepositBTC(
            value
          ) => // FIXME: add checks before executing
        standardResponse(
          request.clientNumber,
          request.timestamp,
          value.sessionId,
          request.operation
        ) >> Sync[F].delay(Result.Empty)
      case TimeoutDepositBTC(
            value
          ) => // FIXME: add checks before executing
        Sync[F].delay(sessionState.underlying.remove(value.sessionId)) >>
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
        ) >> Sync[F].delay(Result.Empty)
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
                  startMintingProcess[F](
                    defaultFromFellowship,
                    defaultFromTemplate,
                    peginSessionInfo.redeemAddress,
                    BigInt(value.amount.toByteArray())
                  )
                )
            )
            .getOrElse(Sync[F].unit)
        } yield Result.Empty
      case PostTBTCMint(
            value
          ) => // FIXME: add checks before executing
        standardResponse(
          request.clientNumber,
          request.timestamp,
          value.sessionId,
          request.operation
        ) >> Sync[F].delay(Result.Empty)
      case TimeoutTBTCMint(
            value
          ) => // FIXME: Add checks before executing
        Sync[F].delay(sessionState.underlying.remove(value.sessionId)) >>
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
        standardResponse(
          request.clientNumber,
          request.timestamp,
          value.sessionId,
          request.operation
        ) >> Sync[F].delay(Result.Empty)
      case ConfirmTBTCMint(
            value
          ) => // FIXME: Add checks before executing
        standardResponse(
          request.clientNumber,
          request.timestamp,
          value.sessionId,
          request.operation
        ) >> Sync[F].delay(Result.Empty)
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
          } yield startClaimingProcess[F](
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
          )).getOrElse(Sync[F].unit)
        } yield Result.Empty
      case PostClaimTx(value) =>
        standardResponse(
          request.clientNumber,
          request.timestamp,
          value.sessionId,
          request.operation
        ) >> Sync[F].delay(Result.Empty)
      case UndoClaimTx(value) =>
        standardResponse(
          request.clientNumber,
          request.timestamp,
          value.sessionId,
          request.operation
        ) >> Sync[F].delay(Result.Empty)
      case ConfirmClaimTx(value) =>
        Sync[F].delay(
          sessionState.underlying.remove(value.sessionId)
        ) >> sessionManager.removeSession(
          value.sessionId,
          PeginSessionStateSuccessfulPegin
        ) >> sendResponse(
          request.clientNumber,
          request.timestamp
        ) // FIXME: this is just a change of state at db level
    }).flatMap(x =>
      Sync[F].delay(
        lastReplyMap.underlying.put(
          (ClientId(request.clientNumber), request.timestamp),
          x
        )
      )
    )

}
