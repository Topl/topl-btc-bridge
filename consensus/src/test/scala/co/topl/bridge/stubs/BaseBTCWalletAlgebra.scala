package co.topl.bridge.stubs

import co.topl.bridge.consensus.core.managers.BTCWalletAlgebra
import cats.effect.IO
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.crypto.ECDigitalSignature
import scodec.bits.ByteVector

class BaseBTCWalletAlgebra extends BTCWalletAlgebra[IO] {

  override def getCurrentPubKeyAndPrepareNext(): IO[(Int, ECPublicKey)] = ???

  override def getCurrentPubKey(): IO[ECPublicKey] = ???

  override def signForIdx(
      idx: Int,
      txBytes: ByteVector
  ): IO[ECDigitalSignature] = ???

}
