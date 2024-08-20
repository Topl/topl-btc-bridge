package co.topl.bridge.consensus.modules

import cats.effect.IO
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PBFTInternalServiceFs2Grpc
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.shared.Empty
import io.grpc.Metadata
import cats.effect.kernel.Ref
import java.security.PublicKey

trait PbftServiceModule {

  def pbftService(
    replicaKeysMap: Map[Int, PublicKey],
    currentView: Ref[IO, Long]) =
    new PBFTInternalServiceFs2Grpc[IO, Metadata] {

      override def prePrepare(
          request: PrePrepareRequest,
          ctx: Metadata
      ): IO[Empty] = ???

      override def prepare(request: PrepareRequest, ctx: Metadata): IO[Empty] =
        ???

      override def commit(request: CommitRequest, ctx: Metadata): IO[Empty] =
        ???

    }

}
