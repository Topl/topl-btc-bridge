package co.topl.bridge

import _root_.quivr.models.Int128
import io.grpc.ManagedChannelBuilder
import cats.effect.kernel.Sync
import fs2.grpc.syntax.all._
import co.topl.bridge.consensus.managers.BTCWalletAlgebra
import cats.effect.kernel.Ref
import quivr.models.KeyPair
import java.util.concurrent.ConcurrentHashMap
import co.topl.bridge.consensus.pbft.PBFTState
import co.topl.shared.ClientId
import java.security.PublicKey
import co.topl.bridge.consensus.service.StateMachineReply.Result

package object consensus {

  class PeginWalletManager[F[_]](val underlying: BTCWalletAlgebra[F])
      extends AnyVal
  class BridgeWalletManager[F[_]](val underlying: BTCWalletAlgebra[F])
      extends AnyVal
  class CurrentView[F[_]](val underlying: Ref[F, Long]) extends AnyVal
  class CurrentToplHeight[F[_]](val underlying: Ref[F, Long]) extends AnyVal
  class CurrentBTCHeight[F[_]](val underlying: Ref[F, Int]) extends AnyVal
  class ToplKeypair(val underlying: KeyPair) extends AnyVal
  class SessionState(val underlying: ConcurrentHashMap[String, PBFTState])
      extends AnyVal
  class PublicApiClientGrpcMap[F[_]](
      val underlying: Map[
        ClientId,
        (PublicApiClientGrpc[F], PublicKey)
      ]
  ) extends AnyVal

  class LastReplyMap(val underlying: ConcurrentHashMap[(ClientId, Long), Result])
      extends AnyVal

  class BTCRetryThreshold(val underlying: Int) extends AnyVal
  class BTCWaitExpirationTime(val underlying: Int) extends AnyVal
  class ToplWaitExpirationTime(val underlying: Int) extends AnyVal
  class BTCConfirmationThreshold(val underlying: Int) extends AnyVal
  class ToplConfirmationThreshold(val underlying: Int) extends AnyVal

  def channelResource[F[_]: Sync](
      address: String,
      port: Int,
      secureConnection: Boolean
  ) =
    (if (secureConnection)
       ManagedChannelBuilder
         .forAddress(address, port)
         .useTransportSecurity()
     else
       ManagedChannelBuilder
         .forAddress(address, port)
         .usePlaintext()).resource[F]

  class Fellowship(val underlying: String) extends AnyVal

  class Template(val underlying: String) extends AnyVal

  sealed trait BifrostCurrencyUnit {
    val amount: Int128
  }


  case class Lvl(amount: Int128) extends BifrostCurrencyUnit
  case class SeriesToken(id: String, amount: Int128) extends BifrostCurrencyUnit
  case class GroupToken(id: String, amount: Int128) extends BifrostCurrencyUnit
  case class AssetToken(groupId: String, seriesId: String, amount: Int128)
      extends BifrostCurrencyUnit

}
