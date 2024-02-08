package co.topl.bridge.stubs

import cats.effect.IO
import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.brambl.models.transaction.IoTransaction
import co.topl.bridge.managers.ToplWalletAlgebra
import com.google.protobuf.ByteString
import io.circe.Json
import quivr.models.KeyPair

class BaseToplWalletAlgebra extends ToplWalletAlgebra[IO] {

  import UnitTestStubs._

  override def createSimpleAssetMintingTransactionFromParams(
      keyPair: KeyPair,
      fromFellowship: String,
      fromTemplate: String,
      someFromInteraction: Option[Int],
      fee: Long,
      ephemeralMetadata: Option[Json],
      commitment: Option[ByteString],
      assetMintingStatement: AssetMintingStatement
  ): IO[IoTransaction] = IO(iotransaction01)
}
