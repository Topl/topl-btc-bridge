package co.topl.bridge.publicapi.modules

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import cats.effect.std.CountDownLatch
import cats.implicits._
import co.topl.bridge.consensus.service.Empty
import co.topl.bridge.consensus.service.ResponseServiceFs2Grpc
import co.topl.bridge.consensus.service.StateMachineReply
import co.topl.bridge.consensus.service.StateMachineReply.Result.MintingStatus
import co.topl.bridge.consensus.service.StateMachineReply.Result.StartSession
import co.topl.bridge.consensus.service.StateMachineReply.Result.SessionNotFound
import co.topl.bridge.publicapi.Main.ConsensusClientMessageId
import co.topl.shared.BridgeCryptoUtils
import co.topl.shared.StartPeginSessionResponse
import io.grpc.Metadata

import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

import co.topl.bridge.consensus.service.Empty

trait ReplyServicesModule {

  def replyService[F[_]: Async](
      replicaKeysMap: Map[Int, PublicKey],
      messageResponseMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        Ref[F, Option[
          StartPeginSessionResponse
        ]]
      ],
      messagesMap: ConcurrentHashMap[
        ConsensusClientMessageId,
        CountDownLatch[F]
      ]
  ) =
    ResponseServiceFs2Grpc.bindServiceResource(
      serviceImpl = new ResponseServiceFs2Grpc[F, Metadata] {
        def deliverResponse(
            request: StateMachineReply,
            ctx: Metadata
        ): F[Empty] = {

          import co.topl.shared.implicits._
          val publicKey = replicaKeysMap(request.replicaNumber)
          for {
            isValidSignature <- BridgeCryptoUtils.verifyBytes(
              publicKey,
              request.signableBytes,
              request.signature.toByteArray
            )
            response = request.result match {
              case StateMachineReply.Result.Empty =>
                ???
              case MintingStatus(_) => // FIXME: Implement this
                ???
              case SessionNotFound(_) =>
                ???
              case StartSession(value) =>
                StartPeginSessionResponse(
                  sessionID = value.sessionId,
                  script = value.script,
                  escrowAddress = value.escrowAddress,
                  descriptor = value.descriptor,
                  minHeight = value.minHeight,
                  maxHeight = value.maxHeight
                )
            }
            ref <- Sync[F].delay(
              messageResponseMap
                .get(ConsensusClientMessageId(request.timestamp))
            )
            _ <- ref.set(Some(response))
            _ <-
              if (isValidSignature) {
                messagesMap
                  .get(ConsensusClientMessageId(request.timestamp))
                  .release
              } else {
                Sync[F].unit
              }
          } yield Empty()
        }
      }
    )
}
