package co.topl.bridge.stubs

import cats.effect.IO
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.pbft.CommitRequest
import co.topl.bridge.consensus.pbft.PrePrepareRequest
import co.topl.bridge.consensus.pbft.PrepareRequest
import co.topl.bridge.shared.Empty
import co.topl.consensus.core.PBFTInternalGrpcServiceClient

class BasePBFTInternalGrpcServiceClient
    extends PBFTInternalGrpcServiceClient[IO] {

  override def prePrepare(request: PrePrepareRequest): IO[Empty] = ???

  override def prepare(request: PrepareRequest): IO[Empty] = ???

  override def commit(request: CommitRequest): IO[Empty] = ???

  override def checkpoint(request: CheckpointRequest): IO[Empty] = ???

}
