package co.topl.bridge.consensus

import org.typelevel.log4cats.Logger
import cats.effect.kernel.Sync
import co.topl.brambl.utils.Encoding
import com.typesafe.config.Config

trait InitUtils {

  def printParams[F[_]: Sync: Logger](
      params: ToplBTCBridgeConsensusParamConfig
  ) = {
    import org.typelevel.log4cats.syntax._
    import cats.implicits._
    for {
      // For each parameter, log its value to info
      _ <- info"Command line arguments"
      _ <- info"btc-blocks-to-recover    : ${params.btcWaitExpirationTime}"
      _ <- info"topl-blocks-to-recover   : ${params.toplWaitExpirationTime}"
      _ <-
        info"btc-confirmation-threshold  : ${params.btcConfirmationThreshold}"
      _ <-
        info"topl-confirmation-threshold : ${params.toplConfirmationThreshold}"
      _ <- info"btc-peg-in-seed-file     : ${params.btcPegInSeedFile}"
      _ <- info"btc-peg-in-password      : ******"
      _ <- info"wallet-seed-file         : ${params.btcWalletSeedFile}"
      _ <- info"wallet-password          : ******"
      _ <- info"topl-wallet-seed-file    : ${params.toplWalletSeedFile}"
      _ <- info"topl-wallet-password     : ******"
      _ <- info"topl-wallet-db           : ${params.toplWalletDb}"
      _ <- info"btc-url                  : ${params.btcUrl}"
      _ <- info"btc-user                 : ${params.btcUser}"
      _ <- info"zmq-host                 : ${params.zmqHost}"
      _ <- info"zmq-port                 : ${params.zmqPort}"
      _ <- info"btc-password             : ******"
      _ <- info"btc-network              : ${params.btcNetwork}"
      _ <- info"topl-network             : ${params.toplNetwork}"
      _ <- info"topl-host                : ${params.toplHost}"
      _ <- info"topl-port                : ${params.toplPort}"
      _ <- info"config-file              : ${params.configurationFile.toPath().toString()}"
      _ <- info"topl-secure-connection   : ${params.toplSecureConnection}"
      _ <- info"minting-fee              : ${params.mintingFee}"
      _ <- info"fee-per-byte             : ${params.feePerByte}"
      _ <- info"abtc-group-id            : ${Encoding.encodeToHex(params.groupId.value.toByteArray)}"
      _ <- info"abtc-series-id           : ${Encoding.encodeToHex(params.seriesId.value.toByteArray)}"
      _ <- info"db-file                  : ${params.dbFile.toPath().toString()}"
    } yield ()
  }

  def replicaHost(implicit conf: Config) =
    conf.getString("bridge.replica.requests.host")
  def replicaPort(implicit conf: Config) =
    conf.getInt("bridge.replica.requests.port")
  def privateKeyFile(implicit conf: Config) =
    conf.getString("bridge.replica.security.privateKeyFile")

  def responseHost(implicit conf: Config) =
    conf.getString("bridge.replica.responses.host")

  def responsePort(implicit conf: Config) =
    conf.getInt("bridge.replica.responses.port")

  def printConfig[F[_]: Sync: Logger](implicit
      conf: Config,
      replicaId: ReplicaId
  ) = {

    import org.typelevel.log4cats.syntax._
    import cats.implicits._
    for {
      _ <- info"Configuration arguments"
      _ <- info"bridge.replica.security.privateKeyFile : ${privateKeyFile}"
      _ <- info"bridge.replica.requests.host           : ${replicaHost}"
      _ <- info"bridge.replica.requests.port           : ${replicaPort}"
      _ <- info"bridge.replica.replicaId               : ${replicaId.id}"
    } yield ()
  }

}
