package co.topl.bridge.services

import cats.effect.kernel.Resource
import cats.effect.IO
import org.bitcoins.crypto.ECPublicKey

object WalletManager {

  def createWallet(): Resource[IO, BTCWallet] = {
    Resource.make(IO(BTCWallet()))(_ => IO.unit)
  }

}

case class BTCWallet() {
  def getNextPubKey(): IO[ECPublicKey] = {
    IO(ECPublicKey.freshPublicKey)
  }
}
