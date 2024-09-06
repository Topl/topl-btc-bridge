package co.topl.bridge.consensus.core.pbft

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
import co.topl.bridge.consensus.shared.BTCWaitExpirationTime
import co.topl.bridge.consensus.core.BitcoinNetworkIdentifiers
import co.topl.bridge.consensus.core.BridgeWalletManager
import co.topl.bridge.consensus.core.CheckpointInterval
import co.topl.bridge.consensus.core.CurrentBTCHeight
import co.topl.bridge.consensus.core.CurrentToplHeight
import co.topl.bridge.consensus.core.CurrentView
import co.topl.bridge.consensus.core.Fellowship
import co.topl.bridge.consensus.core.LastReplyMap
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionMintingTBTCConfirmation
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionStateMintingTBTC
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionStateSuccessfulPegin
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionStateTimeout
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionStateWaitingForBTC
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionWaitingForClaim
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionWaitingForClaimBTCConfirmation
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionWaitingForEscrowBTCConfirmation
import co.topl.bridge.consensus.shared.PeginSessionState.PeginSessionWaitingForRedemption
import co.topl.bridge.consensus.core.PeginWalletManager
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.SessionState
import co.topl.bridge.consensus.core.Template
import co.topl.bridge.consensus.core.ToplKeypair
import co.topl.bridge.consensus.shared.ToplWaitExpirationTime
import co.topl.bridge.consensus.core.controllers.StartSessionController
import co.topl.bridge.consensus.subsystems.monitor.SessionManagerAlgebra
import co.topl.bridge.consensus.core.pbft.ConfirmDepositBTCEvt
import co.topl.bridge.consensus.core.pbft.ConfirmTBTCMintEvt
import co.topl.bridge.consensus.core.pbft.PBFTEvent
import co.topl.bridge.consensus.core.pbft.PBFTState
import co.topl.bridge.consensus.core.pbft.PBFTTransitionRelation
import co.topl.bridge.consensus.core.pbft.PSClaimingBTC
import co.topl.bridge.consensus.core.pbft.PSConfirmingBTCClaim
import co.topl.bridge.consensus.core.pbft.PSConfirmingBTCDeposit
import co.topl.bridge.consensus.core.pbft.PSConfirmingTBTCMint
import co.topl.bridge.consensus.core.pbft.PSMintingTBTC
import co.topl.bridge.consensus.core.pbft.PSWaitingForBTCDeposit
import co.topl.bridge.consensus.core.pbft.PSWaitingForRedemption
import co.topl.bridge.consensus.core.pbft.PostClaimTxEvt
import co.topl.bridge.consensus.core.pbft.PostDepositBTCEvt
import co.topl.bridge.consensus.core.pbft.PostRedemptionTxEvt
import co.topl.bridge.consensus.core.pbft.PostTBTCMintEvt
import co.topl.bridge.consensus.core.pbft.UndoClaimTxEvt
import co.topl.bridge.consensus.core.pbft.UndoDepositBTCEvt
import co.topl.bridge.consensus.core.pbft.UndoTBTCMintEvt
import co.topl.bridge.consensus.shared.persistence.StorageApi
import co.topl.bridge.consensus.shared.MiscUtils
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.service.InvalidInputRes
import co.topl.bridge.consensus.service.StartSessionRes
import co.topl.bridge.consensus.service.StateMachineReply.Result
import co.topl.bridge.consensus.shared.AssetToken
import co.topl.bridge.consensus.shared.Lvl
import co.topl.bridge.shared.BridgeCryptoUtils
import co.topl.bridge.shared.BridgeError
import co.topl.bridge.shared.ClientId
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaId
import co.topl.bridge.shared.StartSessionOperation
import co.topl.bridge.shared.StateMachineRequest
import co.topl.bridge.shared.StateMachineRequest.Operation.ConfirmClaimTx
import co.topl.bridge.shared.StateMachineRequest.Operation.ConfirmDepositBTC
import co.topl.bridge.shared.StateMachineRequest.Operation.ConfirmTBTCMint
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
import co.topl.consensus.core.PBFTInternalGrpcServiceClient
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.core.currency.Satoshis
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger
import scodec.bits.ByteVector

import java.security.{KeyPair => JKeyPair}
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

  private def toEvt(op: StateMachineRequest.Operation)(implicit
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId
  ) = {
    op match {
      case StateMachineRequest.Operation.Empty =>
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

  def waitForProtocol[F[_]: Async: Logger](
      request: co.topl.bridge.shared.StateMachineRequest,
      keyPair: JKeyPair,
      digest: ByteString,
      currentView: Long,
      pbftProtocolClientGrpc: PBFTInternalGrpcServiceClient[F],
      currentSequence: Long
  )(implicit
      replica: ReplicaId,
      storageApi: StorageApi[F],
      replicaCount: ReplicaCount,
      publicApiClientGrpcMap: PublicApiClientGrpcMap[F],
      currentViewRef: CurrentView[F],
      checkpointInterval: CheckpointInterval,
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
  ) = {
    import scala.concurrent.duration._
    import co.topl.bridge.shared.implicits._
    import cats.implicits._
    for {
      _ <- (Async[F].sleep(1.seconds) >>
        isPrepared[F](
          currentView,
          currentSequence
        )).iterateUntil(identity)
      commitRequest = CommitRequest(
        viewNumber = currentView,
        sequenceNumber = currentSequence,
        digest = digest,
        replicaId = replica.id
      )
      signedBytes <- BridgeCryptoUtils.signBytes[F](
        keyPair.getPrivate(),
        commitRequest.signableBytes
      )
      _ <- pbftProtocolClientGrpc.commit(
        commitRequest.withSignature(
          ByteString.copyFrom(signedBytes)
        )
      )
      _ <- (Async[F].sleep(1.second) >> isCommitted[F](
        currentView,
        currentSequence
      )).iterateUntil(identity)
      _ <- executeRequest(
        request
      )
      // here we start the checkpoint
      _ <-
        if (currentSequence % checkpointInterval.underlying == 0)
          for {
            checkpointRequest <- CheckpointRequest(
              sequenceNumber = currentSequence,
              digest = ByteString.copyFrom(createStateDigest(sessionState)),
              replicaId = replica.id
            ).pure[F]
            signedBytes <- BridgeCryptoUtils.signBytes[F](
              keyPair.getPrivate(),
              checkpointRequest.signableBytes
            )
            _ <- pbftProtocolClientGrpc.checkpoint(
              checkpointRequest.withSignature(
                ByteString.copyFrom(signedBytes)
              )
            )
          } yield ()
        else Async[F].unit
    } yield ()
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
