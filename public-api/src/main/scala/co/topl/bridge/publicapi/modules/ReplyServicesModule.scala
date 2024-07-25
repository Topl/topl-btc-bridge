package co.topl.bridge.publicapi.modules

import cats.effect.kernel.Async
import cats.effect.kernel.Sync
import cats.implicits._
import co.topl.bridge.consensus.service.Empty
import co.topl.bridge.consensus.service.ResponseServiceFs2Grpc
import co.topl.bridge.consensus.service.StateMachineReply
import co.topl.bridge.consensus.service.StateMachineReply.Result.MintingStatus
import co.topl.bridge.consensus.service.StateMachineReply.Result.SessionNotFound
import co.topl.bridge.consensus.service.StateMachineReply.Result.StartSession
import co.topl.bridge.publicapi.ConsensusClientMessageId
import co.topl.shared
import co.topl.shared.BridgeCryptoUtils
import co.topl.shared.BridgeError
import co.topl.shared.BridgeResponse
import co.topl.shared.InvalidInput
import co.topl.shared.MintingStatusResponse
import co.topl.shared.SessionNotFoundError
import co.topl.shared.StartPeginSessionResponse
import io.grpc.Metadata
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import cats.effect.kernel.Ref

trait ReplyServicesModule {

  def replyService[F[_]: Async: Logger](
      currentViewRef: Ref[F, Long],
      replicaKeysMap: Map[Int, PublicKey],
      messageVotersMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        ConcurrentHashMap[Int, Int]
      ],
      messageResponseMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        ConcurrentHashMap[Either[
          BridgeError,
          BridgeResponse
        ], LongAdder]
      ]
  ) =
    ResponseServiceFs2Grpc.bindServiceResource(
      serviceImpl = new ResponseServiceFs2Grpc[F, Metadata] {
        def deliverResponse(
            request: StateMachineReply,
            ctx: Metadata
        ): F[Empty] = {

          import co.topl.shared.implicits._
          for {
            _ <- trace"Received response from replica ${request.replicaNumber}"
            publicKey = replicaKeysMap(request.replicaNumber)
            isValidSignature <- BridgeCryptoUtils.verifyBytes(
              publicKey,
              request.signableBytes,
              request.signature.toByteArray
            )
            _ <- trace"Signature is valid"
            _ <-
              if (isValidSignature) {
                val response: Either[BridgeError, BridgeResponse] =
                  request.result match {
                    case StateMachineReply.Result.Empty =>
                      Left(
                        shared.UnknownError(
                          "This should not happen: Empty response"
                        )
                      )
                    case MintingStatus(value) =>
                      Right(
                        MintingStatusResponse(
                          value.mintingStatus,
                          value.address,
                          value.redeemScript
                        )
                      )
                    case StateMachineReply.Result.InvalidInput(value) =>
                      Left(
                        InvalidInput(
                          value.errorMessage
                        )
                      )
                    case SessionNotFound(value) =>
                      Left(SessionNotFoundError(value.sessionId))
                    case StartSession(value) =>
                      Right(
                        StartPeginSessionResponse(
                          sessionID = value.sessionId,
                          script = value.script,
                          escrowAddress = value.escrowAddress,
                          descriptor = value.descriptor,
                          minHeight = value.minHeight,
                          maxHeight = value.maxHeight
                        )
                      )
                  }
                for {
                  _ <- currentViewRef.update(x =>
                    if (x < request.viewNumber) request.viewNumber else x
                  )
                  votersMap <- Sync[F].delay(
                    messageVotersMap
                      .get(
                        ConsensusClientMessageId(request.timestamp)
                      ): ConcurrentHashMap[Int, Int]
                  )
                  voteMap <- Sync[F].delay(
                    messageResponseMap
                      .get(
                        ConsensusClientMessageId(request.timestamp)
                      ): ConcurrentHashMap[Either[
                      BridgeError,
                      BridgeResponse
                    ], LongAdder]
                  )
                  _ <- Option(voteMap)
                    .zip(Option(votersMap))
                    .fold(
                      info"Vote map or voter map not found, vote completed"
                    ) { case (voteMap, votersMap) =>
                      // we check if the replica already voted
                      Option(votersMap.get(request.replicaNumber)).fold(
                        // no vote from this replica yet
                        Sync[F].delay(
                          votersMap
                            .computeIfAbsent(request.replicaNumber, _ => 1)
                        ) >> Sync[F].delay(
                          voteMap
                            .computeIfAbsent(response, _ => new LongAdder())
                            .increment()
                        )
                      ) { _ =>
                        warn"Duplicate vote from replica ${request.replicaNumber}"
                      }
                    }
                } yield ()
              } else {
                error"Invalid signature in response"
              }
          } yield Empty()
        }
      }
    )
}
