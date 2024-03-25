package co.topl.bridge.stubs

import cats.Monad
import cats.implicits._
import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.brambl.models.transaction.IoTransaction
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.shared.ToplNetworkIdentifiers
import com.google.protobuf.ByteString
import io.circe.Json
import quivr.models.KeyPair

class BaseToplWalletAlgebra[F[_]: Monad] extends ToplWalletAlgebra[F] {

  import UnitTestStubs._

  override def setupBridgeWallet(
      networkId: ToplNetworkIdentifiers,
      keyPair: KeyPair,
      userBaseKey: String,
      sessionId: String,
      sha256: String,
      waitTime: Int,
      currentHeight: Int
  ): F[Option[String]] = ???

  override def createSimpleAssetMintingTransactionFromParams(
      keyPair: KeyPair,
      fromFellowship: String,
      fromTemplate: String,
      someFromInteraction: Option[Int],
      fee: Long,
      ephemeralMetadata: Option[Json],
      commitment: Option[ByteString],
      assetMintingStatement: AssetMintingStatement
  ): F[IoTransaction] = iotransaction01.pure[F]
}
