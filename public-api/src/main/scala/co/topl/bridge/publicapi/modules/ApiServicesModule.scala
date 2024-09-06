package co.topl.bridge.publicapi.modules

import cats.effect.IO
import co.topl.bridge.shared.MintingStatusOperation
import co.topl.bridge.shared.StartSessionOperation
import co.topl.bridge.shared.ClientId
import co.topl.bridge.shared.StateMachineServiceGrpcClient
import co.topl.bridge.shared.BridgeContants
import co.topl.bridge.shared.BridgeError
import co.topl.bridge.shared.BridgeResponse
import co.topl.bridge.shared.MintingStatusRequest
import co.topl.bridge.shared.MintingStatusResponse
import co.topl.bridge.shared.SessionNotFoundError
import co.topl.bridge.shared.StartPeginSessionRequest
import co.topl.bridge.shared.StartPeginSessionResponse
import io.circe.Json
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.circe._
import org.http4s.headers.`Content-Type`
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._

trait ApiServicesModule {
  def apiServices(
      consensusGrpcClients: StateMachineServiceGrpcClient[IO]
  )(implicit
      l: Logger[IO],
      clientNumber: ClientId
  ) = {
    import org.http4s.dsl.io._
    implicit val bridgeErrorEntityEncoder: EntityEncoder[IO, BridgeError] =
      new EntityEncoder[IO, BridgeError] {
        override def toEntity(a: BridgeError): Entity[IO] =
          Entity[IO](
            fs2.Stream.fromIterator[IO](
              Json
                .obj(
                  ("error", Json.fromString(a.error))
                )
                .noSpaces
                .getBytes()
                .iterator,
              1024
            )
          )

        override def headers: Headers = Headers(
          `Content-Type`.apply(MediaType.application.json)
        )
      }
    implicit val startSessionRequestEncoder: EntityEncoder[IO, BridgeResponse] =
      new EntityEncoder[IO, BridgeResponse] {
        override def toEntity(a: BridgeResponse): Entity[IO] =
          Entity[IO](a match {
            case r: MintingStatusResponse =>
              fs2.Stream.fromIterator[IO](
                Json
                  .obj(
                    ("mintingStatus", Json.fromString(r.mintingStatus)),
                    ("address", Json.fromString(r.address)),
                    ("redeemScript", Json.fromString(r.redeemScript))
                  )
                  .noSpaces
                  .getBytes()
                  .iterator,
                1024
              )
            case r: StartPeginSessionResponse =>
              fs2.Stream.fromIterator[IO](
                Json
                  .obj(
                    ("sessionID", Json.fromString(r.sessionID)),
                    ("script", Json.fromString(r.script)),
                    ("escrowAddress", Json.fromString(r.escrowAddress)),
                    ("descriptor", Json.fromString(r.descriptor)),
                    ("minHeight", Json.fromLong(r.minHeight)),
                    ("maxHeight", Json.fromLong(r.maxHeight))
                  )
                  .noSpaces
                  .getBytes()
                  .iterator,
                1024
              )
          })

        override def headers: Headers = Headers(
          `Content-Type`.apply(MediaType.application.json)
        )
      }
    HttpRoutes.of[IO] {
      case req @ POST -> Root / BridgeContants.START_PEGIN_SESSION_PATH =>
        implicit val startSessionRequestDecoder
            : EntityDecoder[IO, StartPeginSessionRequest] =
          jsonOf[IO, StartPeginSessionRequest]

        (for {
          _ <-
            info"Received request to start pegin session"
          x <- req.as[StartPeginSessionRequest]
          someResponse <- consensusGrpcClients.startPegin(
            StartSessionOperation(
              None,
              x.pkey,
              x.sha256
            )
          )
          res <- someResponse match {
            case Left(e: BridgeError) =>
              e match {
                case _: SessionNotFoundError =>
                  NotFound(e)
                case _ =>
                  BadRequest(e)
              }
              BadRequest(e)
            case Left(_) =>
              InternalServerError()
            case Right(response) =>
              Ok(response)
          }
        } yield res).handleErrorWith { e =>
          IO(e.printStackTrace()) >>
            error"Error in start pegin session request: ${e.getMessage}" >> BadRequest(
              "Error starting pegin session"
            )
        }
      case req @ POST -> Root / BridgeContants.TOPL_MINTING_STATUS =>
        implicit val mintingStatusRequestDecoder
            : EntityDecoder[IO, MintingStatusRequest] =
          jsonOf[IO, MintingStatusRequest]

        for {
          _ <- trace"Received request for minting status"
          x <- req.as[MintingStatusRequest]
          someResponse <- consensusGrpcClients.mintingStatus(
            MintingStatusOperation(
              x.sessionID
            )
          )
          res <- someResponse match {
            case Left(e: SessionNotFoundError) =>
              error"Session ${e.error} not found" >> NotFound(e.error)
            case Left(e: BridgeError) =>
              error"Bad request error: ${e.error}" >> BadRequest(e.error)
            case Left(e) =>
              error"Error in minting status request: ${e.error}" >> InternalServerError()
              InternalServerError()
            case Right(response) =>
              Ok(response)
          }
        } yield res
    }
  }
}
