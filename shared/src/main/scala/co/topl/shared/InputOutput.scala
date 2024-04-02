package co.topl.shared

/** This class is used to create a new session for a peg-in.
  *
  * @param pkey
  *   The public key of the user.
  * @param sha256
  *   The hash of the secret that is used to redeem the peg-in.
  */
case class StartPeginSessionRequest(
    pkey: String,
    sha256: String
)

case class StartPegoutSessionRequest(
    userBaseKey: String,
    currentHeight: Int,
    sha256: String
)

case class MintingStatusRequest(
    sessionID: String
)

case class MintingStatusResponse(
    mintingStatus: String,
    address: String
)

case class SyncWalletRequest(
    secret: String
)

case class StartPeginSessionResponse(
    sessionID: String,
    script: String,
    escrowAddress: String,
    descriptor: String
)

case class StartPegoutSessionResponse(
    sessionID: String,
    escrowAddress: String
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
    amount: Long
)

case class ConfirmDepositResponse(
    txId: String,
    redeemAddress: String
)
case class ConfirmRedemptionResponse(
    tx: String
)

sealed trait BridgeError extends Throwable {
  val error: String
}

case class SessionNotFoundError(error: String) extends BridgeError
case class InvalidKey(error: String) extends BridgeError
case class InvalidHash(error: String) extends BridgeError
case class InvalidBase58(error: String) extends BridgeError
case class InvalidInput(error: String) extends BridgeError
case class WalletSetupError(error: String) extends BridgeError
