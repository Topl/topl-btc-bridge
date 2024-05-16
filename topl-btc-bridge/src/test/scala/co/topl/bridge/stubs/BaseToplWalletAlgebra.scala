package co.topl.bridge.stubs

import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.brambl.models.transaction.IoTransaction
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.shared.ToplNetworkIdentifiers
import com.google.protobuf.ByteString
import io.circe.Json
import quivr.models.KeyPair
import co.topl.bridge.{Fellowship, Lvl, Template}

class BaseToplWalletAlgebra[F[_]] extends ToplWalletAlgebra[F] {

  def createSimpleAssetMintingTransactionFromParams(
      keyPair: KeyPair,
      fromFellowship: Fellowship,
      fromTemplate: Template,
      someFromInteraction: Option[Int],
      fee: Lvl,
      ephemeralMetadata: Option[Json],
      commitment: Option[ByteString],
      assetMintingStatement: AssetMintingStatement,
      redeemLockAddress: String
  ): F[IoTransaction] = ???

  override def setupBridgeWalletForMinting(
      mintFellowshipName: String,
      mintTemplateName: String,
      keypair: KeyPair,
      sha256: String
  ): F[Option[(String, String)]] = ???

  override def setupBridgeWallet(
      networkId: ToplNetworkIdentifiers,
      keyPair: KeyPair,
      userBaseKey: String,
      fellowshipName: String,
      templateName: String,
      sha256: String,
      waitTime: Int,
      currentHeight: Int
  ): F[Option[String]] = ???
}
