package co.topl.bridge.services

import cats.effect.kernel.Resource
import org.bitcoins.crypto.ECPublicKey
import cats.Monad
import cats.effect.kernel.Sync

object WalletManager {

  def createWallet[F[_]: Sync](): Resource[F, BTCWallet[F]] = {
    Resource.make(Sync[F].point(BTCWallet[F]()))(_ => Sync[F].unit)
  }

}

case class BTCWallet[F[_]: Monad]() {
  def getNextPubKey(): F[ECPublicKey] = {
    Monad[F].point(ECPublicKey.freshPublicKey)
  }
}
