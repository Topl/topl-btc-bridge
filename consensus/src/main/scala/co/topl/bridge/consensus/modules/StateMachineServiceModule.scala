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
import co.topl.brambl.wallet.WalletApi
import co.topl.bridge.consensus.BTCWaitExpirationTime
import co.topl.bridge.consensus.BitcoinNetworkIdentifiers
import co.topl.bridge.consensus.BridgeWalletManager
import co.topl.bridge.consensus.CurrentBTCHeight
import co.topl.bridge.consensus.CurrentToplHeight
import co.topl.bridge.consensus.CurrentView
import co.topl.bridge.consensus.Fellowship
import co.topl.bridge.consensus.LastReplyMap
import co.topl.bridge.consensus.Lvl
import co.topl.bridge.consensus.PeginWalletManager
import co.topl.bridge.consensus.PublicApiClientGrpcMap
import co.topl.bridge.consensus.ReplicaId
import co.topl.bridge.consensus.SessionState
import co.topl.bridge.consensus.Template
import co.topl.bridge.consensus.ToplKeypair
import co.topl.bridge.consensus.ToplWaitExpirationTime
import co.topl.bridge.consensus.managers.SessionManagerAlgebra
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.consensus.persistence.StorageApi
import co.topl.bridge.consensus.service.StateMachineServiceFs2Grpc
import co.topl.bridge.shared.Empty
import co.topl.consensus.PBFTProtocolClientGrpc
import co.topl.shared.BridgeCryptoUtils
import co.topl.shared.ClientId
import co.topl.shared.ReplicaCount
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Metadata
import org.bitcoins.core.currency.CurrencyUnit
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.typelevel.log4cats.Logger

import java.security.MessageDigest
import java.security.{KeyPair => JKeyPair}

trait StateMachineServiceModule {

  import co.topl.bridge.consensus.pbft.StateMachineExecution._

  def stateMachineService(
      keyPair: JKeyPair,
      pbftProtocolClientGrpc: PBFTProtocolClientGrpc[IO],
      idReplicaClientMap: Map[Int, StateMachineServiceFs2Grpc[IO, Metadata]],
      currentSequenceRef: Ref[IO, Long]
  )(implicit
      lastReplyMap: LastReplyMap,
      sessionManager: SessionManagerAlgebra[IO],
      toplKeypair: ToplKeypair,
      publicApiClientGrpcMap: PublicApiClientGrpcMap[IO],
      currentViewRef: CurrentView[IO],
      pegInWalletManager: PeginWalletManager[IO],
      bridgeWalletManager: BridgeWalletManager[IO],
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[IO],
      templateStorageAlgebra: TemplateStorageAlgebra[IO],
      toplWaitExpirationTime: ToplWaitExpirationTime,
      btcNetwork: BitcoinNetworkIdentifiers,
      sessionState: SessionState,
      currentBTCHeightRef: CurrentBTCHeight[IO],
      storageApi: StorageApi[IO],
      replicaId: ReplicaId,
      replicaCount: ReplicaCount,
      tba: TransactionBuilderApi[IO],
      btcWaitExpirationTime: BTCWaitExpirationTime,
      currentToplHeight: CurrentToplHeight[IO],
      walletApi: WalletApi[IO],
      wsa: WalletStateAlgebra[IO],
      groupIdIdentifier: GroupId,
      seriesIdIdentifier: SeriesId,
      utxoAlgebra: GenusQueryAlgebra[IO],
      channelResource: Resource[IO, ManagedChannel],
      defaultMintingFee: Lvl,
      defaultFromFellowship: Fellowship,
      defaultFromTemplate: Template,
      bitcoindInstance: BitcoindRpcClient,
      defaultFeePerByte: CurrencyUnit,
      logger: Logger[IO]
  ) = StateMachineServiceFs2Grpc.bindServiceResource(
    serviceImpl = new StateMachineServiceFs2Grpc[IO, Metadata] {
      // log4cats syntax
      import org.typelevel.log4cats.syntax._

      def executeRequest(
          request: co.topl.bridge.shared.StateMachineRequest,
          ctx: Metadata
      ): IO[Empty] = {
        Option(
          lastReplyMap.underlying.get(
            (ClientId(request.clientNumber), request.timestamp)
          )
        ) match {
          case Some(result) => // we had a cached response
            for {
              viewNumber <- currentViewRef.underlying.get
              _ <- debug"Request.clientNumber: ${request.clientNumber}"
              _ <- publicApiClientGrpcMap
                .underlying(ClientId(request.clientNumber))
                ._1
                .replyStartPegin(request.timestamp, viewNumber, result)
            } yield Empty()
          case None =>
            for {
              currentView <- currentViewRef.underlying.get
              currentSequence <- currentSequenceRef.updateAndGet(_ + 1)
              currentPrimary = currentView % replicaCount.value
              _ <-
                if (currentPrimary != replicaId.id)
                  // we are not the primary, forward the request
                  idReplicaClientMap(
                    replicaId.id
                  ).executeRequest(request, ctx)
                else {
                  import co.topl.shared.implicits._
                  val prePrepareRequest = PrePrepareRequest(
                    viewNumber = currentView,
                    sequenceNumber = currentSequence,
                    digest = ByteString.copyFrom(
                      MessageDigest
                        .getInstance("SHA-256")
                        .digest(request.signableBytes)
                    ),
                    payload = Some(request)
                  )
                  val prepareRequest = PrepareRequest(
                    viewNumber = currentView,
                    sequenceNumber = currentSequence,
                    digest = prePrepareRequest.digest,
                    replicaId = replicaId.id
                  )
                  (for {
                    signedPrePreparedBytes <- BridgeCryptoUtils.signBytes[IO](
                      keyPair.getPrivate(),
                      prePrepareRequest.signableBytes
                    )
                    signedPreparedBytes <- BridgeCryptoUtils.signBytes[IO](
                      keyPair.getPrivate(),
                      prepareRequest.signableBytes
                    )
                    signedprePrepareRequest = prePrepareRequest.withSignature(
                      ByteString.copyFrom(signedPrePreparedBytes)
                    )
                    signedPrepareRequest = prepareRequest.withSignature(
                      ByteString.copyFrom(signedPreparedBytes)
                    )
                    _ <- pbftProtocolClientGrpc.prePrepare(
                      signedprePrepareRequest
                    )
                    _ <- storageApi.insertPrePrepareMessage(
                      signedprePrepareRequest
                    )
                    _ <- pbftProtocolClientGrpc.prepare(
                      signedPrepareRequest
                    )
                    _ <- storageApi.insertPrepareMessage(signedPrepareRequest)

                  } yield ()) >> IO.asyncForIO.start(
                    waitForProtocol[IO](
                      request,
                      keyPair,
                      prePrepareRequest.digest,
                      prePrepareRequest.viewNumber,
                      pbftProtocolClientGrpc,
                      prePrepareRequest.sequenceNumber
                    )
                  ) >> IO.pure(Empty())
                }
            } yield Empty()

        }
      }
    }
  )

}
