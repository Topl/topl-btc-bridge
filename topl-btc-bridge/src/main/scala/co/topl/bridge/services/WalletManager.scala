package co.topl.bridge.services

import cats.effect.kernel.Ref
import cats.effect.kernel.Sync
import co.topl.shared.utils.KeyGenerationUtils
import org.bitcoins.core.hd.HDPath
import org.bitcoins.crypto.ECDigitalSignature
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.crypto.HashType
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import scodec.bits.ByteVector

trait BTCWalletAlgebra[F[_]] {
  def getCurrentPubKeyAndPrepareNext(): F[(Int, ECPublicKey)]
  def getCurrentPubKey(): F[ECPublicKey]
  def signForIdx(idx: Int, txBytes: ByteVector): F[ECDigitalSignature]
}

object BTCWalletImpl {

  def make[F[_]: Sync](
      km: BIP39KeyManager
  ): BTCWalletAlgebra[F] = {

    val currentIdxRef = Ref[F].of(0)
    new BTCWalletAlgebra[F] {
      override def getCurrentPubKeyAndPrepareNext(): F[(Int, ECPublicKey)] = {
        import cats.implicits._
        for {
          currentIdx <- currentIdxRef
          idx <- currentIdx.getAndUpdate(_ + 1)
          pubKey <- KeyGenerationUtils.generateKey(km, idx)
        } yield (idx, pubKey)
      }

      override def getCurrentPubKey(): F[ECPublicKey] = {
        import cats.implicits._
        for {
          currentIdx <- currentIdxRef
          idx <- currentIdx.get
          pubKey <- KeyGenerationUtils.generateKey(km, idx)
        } yield pubKey
      }

      override def signForIdx(idx: Int, txBytes: ByteVector): F[ECDigitalSignature] = {
        import cats.implicits._
        for {
          signed <- Sync[F].delay(
            km.toSign(HDPath.fromString("m/84'/1'/0'/0/" + idx))
              .sign(txBytes)
          )
          canonicalSignature <- Sync[F].delay(
            ECDigitalSignature(
              signed.bytes ++ ByteVector.fromByte(HashType.sigHashAll.byte)
            )
          )
        } yield canonicalSignature

      }

    }
  }
}
