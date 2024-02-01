package co.topl.bridge.services

import cats.effect.kernel.Async
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
import scodec.bits.ByteVector

case class SessionInfo(
    bridgePKey: String,
    userPKey: String,
    secretHash: String,
    scriptAsm: String,
    address: String
)

trait StartSessionModule {

  def createSessionInfo(
      sha256: String,
      userPKey: String,
      bridgePKey: String,
      btcNetwork: BitcoinNetworkIdentifiers
  ): SessionInfo = {
    val hash = ByteVector.fromHex(sha256).get
    val asm =
      BitcoinUtils.buildScriptAsm(
        ECPublicKey.fromHex(userPKey),
        ECPublicKey.fromHex(bridgePKey),
        hash,
        1000L
      )
    val scriptAsm = BytesUtil.toByteVector(asm)
    val scriptHash = CryptoUtil.sha256(scriptAsm)
    val push_op = BitcoinScriptUtil.calculatePushOp(hash)
    val address = Bech32Address
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
    SessionInfo(
      bridgePKey,
      userPKey,
      sha256,
      scriptAsm.toHex,
      address
    )
  }

  def startSession[F[_]: Async](
      request: Request[F],
      keyfile: String,
      password: String,
      sessionManager: SessionManagerAlgebra[F],
      btcNetwork: BitcoinNetworkIdentifiers
  ) = {
    implicit val startSessionRequestDecoder
        : EntityDecoder[F, StartSessionRequest] =
      jsonOf[F, StartSessionRequest]
    import io.circe.syntax._
    import cats.implicits._
    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._
    (for {
      req <- request.as[StartSessionRequest]
      km <- KeyGenerationUtils.loadKeyManager[F](
        btcNetwork,
        keyfile,
        password
      )
      newKey <- KeyGenerationUtils.generateKey[F](km, 1)
      sessionInfo = createSessionInfo(
        req.sha256,
        req.pkey,
        newKey,
        btcNetwork
      )
      sessionId <- sessionManager.createNewSession(sessionInfo)
      resp <- Ok(
        StartSessionResponse(
          sessionId,
          sessionInfo.scriptAsm,
          sessionInfo.address,
          BitcoinUtils.createDescriptor(newKey, req.pkey, req.sha256)
        ).asJson
      )
    } yield resp).handleErrorWith(e => {
      e.printStackTrace()
      BadRequest("Error")
    })
  }

}
