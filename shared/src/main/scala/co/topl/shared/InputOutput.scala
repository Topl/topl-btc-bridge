package co.topl.shared

case class StartSessionRequest(
    pkey: String,
    sha256: String
)

case class StartSessionResponse(
    sessionID: String,
    script: String,
    escrowAddress: String,
    descriptor: String
)

case class ConfirmRedemptionRequest(
    sessionID: String,
    inputTxId: String,
    inputIndex: Int,
    feePerByte: Int,
    amount: Long,
    secret: String
)

case class ConfirmDepositRequest(
    sessionID: String,
    groupTokenUtxoTxId: String,
    groupTokenUtxoIdx: Int,
    seriesTokenUtxoTxId: String,
    seriesTokenUtxoIdx: Int,
    amount: Long
)

case class ConfirmRedemptionResponse(
    tx: String
)

sealed trait BridgeError extends Throwable

case class SessionNotFoundError(error: String) extends BridgeError
case class InvalidKey(error: String) extends BridgeError
case class InvalidHash(error: String) extends BridgeError
case class InvalidBase58(error: String) extends BridgeError
case class InvalidInput(error: String) extends BridgeError
