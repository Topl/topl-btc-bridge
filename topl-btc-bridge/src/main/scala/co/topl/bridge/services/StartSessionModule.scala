package co.topl.bridge.services

import cats.effect.IO
import cats.effect.kernel.Resource
import co.topl.bridge.BitcoinUtils
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.StartSessionRequest
import co.topl.shared.StartSessionResponse
import co.topl.shared.utils.KeyGenerationUtils
import io.circe.generic.auto._
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.core.protocol.script.WitnessScriptPubKey
import org.bitcoins.core.script.constant.OP_0
import org.bitcoins.core.script.constant.ScriptConstant
import org.bitcoins.core.util.BitcoinScriptUtil
import org.bitcoins.core.util.BytesUtil
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.crypto._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._
import scodec.bits.ByteVector

import java.util.UUID
import scala.collection.mutable

case class SessionInfo(
    bridgePKey: String,
    userPKey: String,
    secretHash: String,
    scriptAsm: String,
    address: String
)

trait StartSessionModule {

  def startSession(
      req: Request[IO],
      sessionManager: Resource[IO, mutable.Map[String, SessionInfo]],
      btcNetwork: BitcoinNetworkIdentifiers
  ) = {
    implicit val startSessionRequestDecoder
        : EntityDecoder[IO, StartSessionRequest] =
      jsonOf[IO, StartSessionRequest]
    import io.circe.syntax._
    (for {
      req <- req.as[StartSessionRequest]
      sessionId <- IO(UUID.randomUUID().toString)
      password <- IO(sessionId)
      newKey <- KeyGenerationUtils.generateKey[IO](
        btcNetwork,
        sessionId + ".json",
        password
      )
      hash = ByteVector.fromHex(req.sha256).get
      asm <- IO(
        BitcoinUtils.buildScriptAsm(
          ECPublicKey.fromHex(req.pkey),
          ECPublicKey.fromHex(newKey),
          hash,
          1000L
        )
      )
      scriptAsm = BytesUtil.toByteVector(asm)
      scriptHash = CryptoUtil.sha256(scriptAsm)
      push_op = BitcoinScriptUtil.calculatePushOp(hash)
      address = Bech32Address
        .apply(
          WitnessScriptPubKey
            .apply(
              Seq(OP_0) ++
                push_op ++
                Seq(ScriptConstant.fromBytes(scriptHash.bytes))
            ),
          btcNetwork.btcNetwork
        )
        .value
      sessionInfo = SessionInfo(
        newKey,
        req.pkey,
        req.sha256,
        scriptAsm.toHex,
        address
      )
      _ <- sessionManager.use(map => IO(map.update(sessionId, sessionInfo)))
      resp <- Ok(
        StartSessionResponse(
          sessionId,
          scriptAsm.toHex,
          address,
          BitcoinUtils.createDescriptor(newKey, req.pkey, req.sha256)
        ).asJson
      )
    } yield resp).handleErrorWith(e => {
      e.printStackTrace()
      BadRequest("Error")
    })
  }

}
