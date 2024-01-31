package co.topl.shared.utils

import cats.effect.kernel.Sync
import co.topl.shared.BitcoinNetworkIdentifiers
import org.bitcoins.core.crypto.MnemonicCode
import org.bitcoins.core.hd.BIP32Path
import org.bitcoins.core.hd.HDAccount
import org.bitcoins.core.hd.HDPath
import org.bitcoins.core.hd.HDPurposes
import org.bitcoins.core.wallet.keymanagement.KeyManagerParams
import org.bitcoins.crypto.AesPassword
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import scodec.bits.ByteVector
import org.bitcoins.crypto.ECDigitalSignature
import org.bitcoins.crypto.HashType

object KeyGenerationUtils {

  def loadKeyAndSign[F[_]: Sync](
      btcNetwork: BitcoinNetworkIdentifiers,
      seedFile: String,
      password: String,
      txBytes: ByteVector
  ): F[String] = {
    import cats.implicits._
    for {
      seedPath <- Sync[F].delay(
        new java.io.File(seedFile).getAbsoluteFile.toPath
      )
      purpose = HDPurposes.SegWit
      kmParams = KeyManagerParams(seedPath, purpose, btcNetwork.btcNetwork)
      aesPasswordOpt = Some(AesPassword.fromString(password))
      km <- Sync[F].fromEither(
        BIP39KeyManager
          .fromParams(
            kmParams,
            aesPasswordOpt,
            None
          )
          .left
          .map(_ => new IllegalArgumentException("Invalid params"))
      )
      signed <- Sync[F].delay(
        km.toSign(HDPath.fromString("m/84'/1'/0'/0/0")).sign(txBytes)
      )
      canonicalSignature <- Sync[F].delay(ECDigitalSignature(
        signed.bytes ++ ByteVector.fromByte(HashType.sigHashAll.byte)
      ))
    } yield canonicalSignature.hex
  }

  def generateKey[F[_]: Sync](
      btcNetwork: BitcoinNetworkIdentifiers,
      seedFile: String,
      password: String
  ): F[String] = {
    import cats.implicits._
    for {
      seedPath <- Sync[F].delay(
        new java.io.File(seedFile).getAbsoluteFile.toPath
      )
      purpose = HDPurposes.SegWit
      kmParams = KeyManagerParams(seedPath, purpose, btcNetwork.btcNetwork)
      aesPasswordOpt = Some(AesPassword.fromString(password))
      entropy = MnemonicCode.getEntropy256Bits
      mnemonic = MnemonicCode.fromEntropy(entropy)
      km <- Sync[F].fromEither(
        BIP39KeyManager.initializeWithMnemonic(
          aesPasswordOpt,
          mnemonic,
          None,
          kmParams
        )
      )
      hdAccount <- Sync[F].fromOption(
        HDAccount.fromPath(
          BIP32Path.fromString("m/84'/1'/0'")
        ) // this is the standard account path for segwit
        ,
        new IllegalArgumentException("Invalid account path")
      )
      pKey <- Sync[F].delay(
        km.deriveXPub(hdAccount)
          .get
          .deriveChildPubKey(BIP32Path.fromString("m/0/0"))
          .get
          .key
          .hex
      )
    } yield (pKey)
  }
}
