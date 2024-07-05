package co.topl.shared

import cats.effect.kernel.Sync
import org.bouncycastle.openssl.PEMParser

import java.io.FileReader
import java.security.PrivateKey
import java.security.Signature

object BridgeCryptoUtils {

  import cats.implicits._

  def getPrivateKeyFromPemFile[F[_]: Sync](filePath: String): F[PrivateKey] =
    for {
      pemReader <- Sync[F].delay(new PEMParser(new FileReader(filePath)))
      pemObject <- Sync[F].delay(pemReader.readObject())
      converter <- Sync[F].delay(
        new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter()
      )
      privateKey <- Sync[F].delay(
        converter.getKeyPair(
          pemObject.asInstanceOf[org.bouncycastle.openssl.PEMKeyPair]
        ).getPrivate()
      )
    } yield privateKey

  def signBytes[F[_]: Sync](
      privateKey: PrivateKey,
      bytes: Array[Byte]
  ): F[Array[Byte]] = {
    val signature = Signature.getInstance("SHA256withECDSA", "BC")
    for {
      _ <- Sync[F].delay(signature.initSign(privateKey))
      _ <- Sync[F].delay(signature.update(bytes))
      signedBytes <- Sync[F].delay(signature.sign())
    } yield signedBytes
  }
}
