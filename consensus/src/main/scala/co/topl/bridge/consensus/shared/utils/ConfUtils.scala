package co.topl.bridge.consensus.shared.utils

import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import co.topl.bridge.consensus.core.PublicApiClientGrpc
import co.topl.bridge.consensus.core.PublicApiClientGrpcImpl
import co.topl.bridge.shared.BridgeCryptoUtils
import co.topl.bridge.shared.ClientCount
import co.topl.bridge.shared.ClientId
import co.topl.bridge.shared.ReplicaCount
import co.topl.bridge.shared.ReplicaId
import com.typesafe.config.Config
import io.grpc.ManagedChannelBuilder
import org.typelevel.log4cats.Logger

import java.security.KeyPair
import java.security.PublicKey

object ConfUtils {

  def createReplicaPublicKeyMap[F[_]: Sync](
      conf: Config
  )(implicit replicaCount: ReplicaCount): F[Map[Int, PublicKey]] = {
    import cats.implicits._
    (for (i <- 0 until replicaCount.value) yield {
      val publicKeyFile = conf.getString(
        s"bridge.replica.consensus.replicas.$i.publicKeyFile"
      )
      for {
        keyPair <- BridgeCryptoUtils.getPublicKey(publicKeyFile).allocated
      } yield (i, keyPair._1)
    }).toList.sequence.map(x => Map(x: _*))
  }

  def createClientMap[F[_]: Async: Logger](
      replicaKeyPair: KeyPair,
      conf: Config
  )(implicit
      replicaId: ReplicaId,
      clientCount: ClientCount
  ): Resource[F, Map[ClientId, (PublicApiClientGrpc[F], PublicKey)]] = {
    import cats.implicits._
    (for (i <- 0 until clientCount.value) yield {
      val publicKeyFile = conf.getString(
        s"bridge.replica.clients.clients.$i.publicKeyFile"
      )
      val host = conf.getString(s"bridge.replica.clients.clients.$i.host")
      val port = conf.getInt(s"bridge.replica.clients.clients.$i.port")
      val secure = conf.getBoolean(
        s"bridge.replica.clients.clients.$i.secure"
      )
      import fs2.grpc.syntax.all._
      for {
        publicKey <- BridgeCryptoUtils.getPublicKey(publicKeyFile)
        channel <-
          (if (secure)
             ManagedChannelBuilder
               .forAddress(host, port)
               .useTransportSecurity()
           else
             ManagedChannelBuilder
               .forAddress(host, port)
               .usePlaintext()).resource[F]
        publicApiGrpc <- PublicApiClientGrpcImpl.make[F](
          channel,
          replicaKeyPair
        )
      } yield (new ClientId(i) -> (publicApiGrpc, publicKey))
    }).toList.sequence.map(x => Map(x: _*))
  }

}
