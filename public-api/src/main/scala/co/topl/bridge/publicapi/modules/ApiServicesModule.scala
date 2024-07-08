package co.topl.bridge.publicapi.modules

import cats.effect.IO
import co.topl.bridge.consensus.service.StartSessionOperation
import co.topl.bridge.publicapi.ClientNumber
import co.topl.bridge.publicapi.ConsensusClientGrpc
import co.topl.shared.BridgeContants
import co.topl.shared.StartPeginSessionRequest
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s._
import org.http4s.circe._
import co.topl.shared.StartPeginSessionResponse

trait ApiServicesModule {
  def apiServices(
      consensusGrpc: ConsensusClientGrpc[IO]
  )(implicit
      clientNumber: ClientNumber
  ) = {
    import org.http4s.dsl.io._
    HttpRoutes.of[IO] {
      case req @ POST -> Root / BridgeContants.START_PEGIN_SESSION_PATH =>
        implicit val startSessionRequestDecoder
            : EntityDecoder[IO, StartPeginSessionRequest] =
          jsonOf[IO, StartPeginSessionRequest]
        implicit val startSessionRequestEncoder
            : EntityEncoder[IO, StartPeginSessionResponse] =
          jsonEncoderOf[IO, StartPeginSessionResponse]

        for {
          x <- req.as[StartPeginSessionRequest]
          someResponse <- consensusGrpc.startPegin(
            StartSessionOperation(
              x.pkey,
              x.sha256
            )
          )
          res <- Ok(someResponse.get)
        } yield res
      case req @ POST -> Root / BridgeContants.TOPL_MINTING_STATUS =>
        ???
    }
  }
}
