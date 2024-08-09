package co.topl.bridge.consensus.pbft



object PBFTTransitionRelation {
  import cats.implicits._

  def handlePBFTEvent(
      currentState: PBFTState,
      pbftEvent: PBFTEvent
  ): Option[PBFTState] =
    (currentState, pbftEvent) match {
      case (
            cs: PSWaitingForBTCDeposit,
            ev: PostDepositBTCEvt
          ) =>
        PSConfirmingBTCDeposit(
          startWaitingBTCBlockHeight = cs.height,
          depositBTCBlockHeight = ev.height,
          currentWalletIdx = cs.currentWalletIdx,
          scriptAsm = cs.scriptAsm,
          escrowAddress = cs.escrowAddress,
          redeemAddress = cs.redeemAddress,
          claimAddress = cs.claimAddress,
          btcTxId = ev.txId,
          btcVout = ev.vout,
          amount = ev.amount
        ).some
      case (
            cs: PSConfirmingBTCDeposit,
            _: UndoDepositBTCEvt
          ) =>
        PSWaitingForBTCDeposit(
          height = cs.startWaitingBTCBlockHeight,
          currentWalletIdx = cs.currentWalletIdx,
          scriptAsm = cs.scriptAsm,
          escrowAddress = cs.escrowAddress,
          redeemAddress = cs.redeemAddress,
          claimAddress = cs.claimAddress
        ).some
      case (
            cs: PSConfirmingBTCDeposit,
            _: ConfirmDepositBTCEvt
          ) =>
        PSMintingTBTC(
          startWaitingBTCBlockHeight = cs.startWaitingBTCBlockHeight,
          currentWalletIdx = cs.currentWalletIdx,
          scriptAsm = cs.scriptAsm,
          redeemAddress = cs.redeemAddress,
          claimAddress = cs.claimAddress,
          btcTxId = cs.btcTxId,
          btcVout = cs.btcVout,
          amount = cs.amount
        ).some
      case (
            cs: PSMintingTBTC,
            ev: PostTBTCMintEvt
          ) =>
        PSConfirmingTBTCMint(
          startWaitingBTCBlockHeight = cs.startWaitingBTCBlockHeight,
          depositTBTCBlockHeight = ev.height,
          currentWalletIdx = cs.currentWalletIdx,
          scriptAsm = cs.scriptAsm,
          redeemAddress = cs.redeemAddress,
          claimAddress = cs.claimAddress,
          btcTxId = cs.btcTxId,
          btcVout = cs.btcVout,
          utxoTxId = ev.utxoTxId,
          utxoIndex = ev.utxoIdx,
          amount = ev.amount
        ).some
      case (
            cs: PSConfirmingTBTCMint,
            _: ConfirmTBTCMintEvt
          ) =>
        PSWaitingForRedemption(
          tbtcMintBlockHeight = cs.depositTBTCBlockHeight,
          currentWalletIdx = cs.currentWalletIdx,
          scriptAsm = cs.scriptAsm,
          redeemAddress = cs.redeemAddress,
          claimAddress = cs.claimAddress,
          btcTxId = cs.btcTxId,
          btcVout = cs.btcVout,
          utxoTxId = cs.utxoTxId,
          utxoIndex = cs.utxoIndex,
          amount = cs.amount
        ).some
      case (
            cs: PSConfirmingTBTCMint,
            _: UndoTBTCMintEvt
          ) =>
        PSConfirmingTBTCMint(
          startWaitingBTCBlockHeight = cs.startWaitingBTCBlockHeight,
          depositTBTCBlockHeight = cs.depositTBTCBlockHeight,
          currentWalletIdx = cs.currentWalletIdx,
          scriptAsm = cs.scriptAsm,
          redeemAddress = cs.redeemAddress,
          claimAddress = cs.claimAddress,
          btcTxId = cs.btcTxId,
          btcVout = cs.btcVout,
          utxoTxId = cs.utxoTxId,
          utxoIndex = cs.utxoIndex,
          amount = cs.amount
        ).some
      case (
            cs: PSConfirmingTBTCMint,
            ev: PostRedemptionTxEvt
          ) =>
        PSClaimingBTC(
          someStartBtcBlockHeight = None,
          secret = ev.secret,
          currentWalletIdx = cs.currentWalletIdx,
          btcTxId = cs.btcTxId,
          btcVout = cs.btcVout,
          scriptAsm = cs.scriptAsm,
          amount = ev.amount,
          claimAddress = cs.claimAddress
        ).some
      case (
            cs: PSWaitingForRedemption,
            ev: PostRedemptionTxEvt
          ) =>
        PSClaimingBTC(
          someStartBtcBlockHeight = None,
          secret = ev.secret,
          currentWalletIdx = cs.currentWalletIdx,
          btcTxId = cs.btcTxId,
          btcVout = cs.btcVout,
          scriptAsm = cs.scriptAsm,
          amount = ev.amount,
          claimAddress = cs.claimAddress
        ).some
      case (
            cs: PSClaimingBTC,
            evt: PostClaimTxEvt
          ) =>
        PSConfirmingBTCClaim(
          claimBTCBlockHeight = evt.height,
          secret = cs.secret,
          currentWalletIdx = cs.currentWalletIdx,
          btcTxId = cs.btcTxId,
          btcVout = cs.btcVout,
          scriptAsm = cs.scriptAsm,
          amount = cs.amount,
          claimAddress = cs.claimAddress
        ).some
      case (_, _) => none
    }

}
