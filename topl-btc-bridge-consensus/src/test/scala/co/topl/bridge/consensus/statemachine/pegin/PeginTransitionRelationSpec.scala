package co.topl.bridge.consensus.statemachine.pegin

import cats.effect.IO
import cats.effect.kernel.Async
import co.topl.bridge.consensus.AssetToken
import munit.CatsEffectSuite
import org.bitcoins.core.protocol.Bech32Address
import scala.annotation.nowarn
import co.topl.brambl.utils.Encoding
import co.topl.bridge.consensus.controllers.SharedData
import co.topl.brambl.syntax._
import co.topl.bridge.consensus.statemachine.pegin.{BifrostFundsDeposited, FSMTransitionTo, MintingTBTCConfirmation, WaitingForRedemption, BTCFundsWithdrawn, MintingTBTC, BifrostFundsWithdrawn, WaitingForEscrowBTCConfirmation, PeginTransitionRelation, EndTransition, BlockchainEvent, WaitingForClaim, WaitingForClaimBTCConfirmation, PeginStateMachineState, BTCFundsDeposited, WaitingForBTC, NewToplBlock, NewBTCBlock}

class PeginTransitionRelationSpec extends CatsEffectSuite with SharedData {

  val escrowAddress =
    "bcrt1qsc9qvqvlswpzlvf4t80g05l2la2cykazmdcur45st5g339vw6aps47j7sw"
  val escrowAddressPubkey = Bech32Address.fromString(escrowAddress).scriptPubKey

  val escrowAddressOther =
    "bcrt1q0xlvz3kxy9vyx4ylghajrvwuyqkspn7pdsch20jn5wjjkhcensus805640"

  val redeemAddress =
    "ptetP7jshHVptQYvKZfMjruCBvWENnp4KbUT7t83c7pk3Y5uuo9GwjxnzERW"

  val redeemAddressOther =
    "ptetP7jshHTzLLp81RbPkeHKWFJWeE3ijH94TAmiBRPTUTj2htC31NyEWU8p"

  val claimAddress =
    "bcrt1q0xlvz3kxy9vyx4ylghajrvwuyqkspn7pdsch20jn5wjjkhcensus805640"

  val claimAddressPubkey = Bech32Address.fromString(claimAddress).scriptPubKey

  @nowarn // just dummy function
  def transitionToEffect[F[_]: Async](
      currentState: PeginStateMachineState,
      blockchainEvent: BlockchainEvent
  ) = Async[F].unit

  import org.bitcoins.core.currency.SatoshisLong

  test(
    "PeginTransitionRelation should go from WaitingForBTC to WaitingForEscrowBTCConfirmation on deposited funds"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForBTC(1, 1, "", escrowAddress, redeemAddress, claimAddress),
          BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[WaitingForEscrowBTCConfirmation]
    )
  }

  test(
    "PeginTransitionRelation should not transition from WaitingForBTC when the funds are not for the escrow address"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForBTC(
            1,
            1,
            "",
            escrowAddressOther,
            redeemAddress,
            claimAddress
          ),
          BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  // WaitingForBTC -> EndTransition when height difference bigger than expiration time
  test(
    "PeginTransitionRelation should transition from WaitingForBTC to EndTransition when the height difference is bigger than expiration time"
  ) {
    assert(
      (PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForBTC(1, 1, "", escrowAddress, redeemAddress, claimAddress),
          NewBTCBlock(102)
        )(transitionToEffect[IO](_, _))
        .get
        .isInstanceOf[EndTransition[IO]]: @nowarn)
    )
  }

  // WaitingForBTC not transition on Bifrost events
  test(
    "PeginTransitionRelation should not transition from WaitingForBTC on Bifrost events"
  ) {
    import co.topl.brambl.syntax._
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForBTC(1, 1, "", escrowAddress, redeemAddress, claimAddress),
          BifrostFundsDeposited(
            currentToplBlockHeight =
              0L, // Assuming a placeholder value for the missing argument
            address = redeemAddress,
            utxoTxId = "utxoTxId",
            utxoIndex = 0,
            amount = AssetToken("groupId", "seriesId", 100L)
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
        PeginTransitionRelation
          .handleBlockchainEvent[IO](
            WaitingForBTC(1, 1, "", escrowAddress, redeemAddress, claimAddress),
            BifrostFundsWithdrawn(
              "bifrostTxId",
              0,
              "topl-secret",
              AssetToken("groupId", "seriesId", 100L)
            )
          )(transitionToEffect[IO](_, _))
          .isEmpty
    )
  }

  test(
    "PeginTransitionRelation should transition from WaitingForRedemption to BifrostFundsWithdrawn"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForRedemption(
            currentTolpBlockHeight = 1L,
            currentWalletIdx = 0,
            scriptAsm = "",
            redeemAddress = redeemAddress,
            claimAddress = claimAddress,
            btcTxId = "txId",
            btcVout = 0L,
            utxoTxId = "bifrostTxId",
            utxoIndex = 0, // Added missing utxoIndex parameter
            amount = AssetToken("groupId", "seriesId", 100L)
          ),
          BifrostFundsWithdrawn(
            "bifrostTxId",
            0,
            "topl-secret",
            AssetToken("groupId", "seriesId", 100L)
          )
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[WaitingForClaim]
    )
  }

  test(
    "PeginTransitionRelation should transition from WaitingForRedemption to EndTransition when the height difference is bigger than expiration time"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForRedemption(
            currentTolpBlockHeight = 1L,
            currentWalletIdx = 0,
            scriptAsm = "",
            redeemAddress = redeemAddress,
            claimAddress = claimAddress,
            btcTxId = "txId",
            btcVout = 0L,
            utxoTxId = "bifrostTxId",
            utxoIndex = 0,
            amount = AssetToken("groupId", "seriesId", 100L)
          ),
          NewToplBlock(2002)
        )(transitionToEffect[IO](_, _))
        .get
        .isInstanceOf[EndTransition[IO]]: @nowarn
    )
  }

  test(
    "PeginTransitionRelation should NOT transition from WaitingForRedemption to BifrostFundsWithdrawn if guard fails"
  ) {
    import co.topl.brambl.syntax._
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForRedemption(
            currentTolpBlockHeight = 1L,
            currentWalletIdx = 0,
            scriptAsm = "",
            redeemAddress = redeemAddress,
            claimAddress = claimAddress,
            btcTxId = "txId",
            btcVout = 0L,
            utxoTxId = "bifrostTxId",
            utxoIndex = 0,
            amount = AssetToken("groupId", "seriesId", 100L)
          ),
          BifrostFundsWithdrawn(
            "bifrostTxIdDifferent",
            0,
            "topl-secret",
            AssetToken("groupId", "seriesId", 100L)
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
        PeginTransitionRelation
          .handleBlockchainEvent[IO](
            WaitingForRedemption(
              currentTolpBlockHeight = 1L,
              currentWalletIdx = 0,
              scriptAsm = "",
              redeemAddress = redeemAddress,
              claimAddress = claimAddress,
              btcTxId = "txId",
              btcVout = 0L,
              utxoTxId = "bifrostTxId",
              utxoIndex = 0,
              amount = AssetToken("groupId", "seriesId", 100L)
            ),
            BifrostFundsWithdrawn(
              "bifrostTxId",
              1,
              "topl-secret",
              AssetToken("groupId", "seriesId", 100L)
            )
          )(transitionToEffect[IO](_, _))
          .isEmpty
    )
  }

  // WaitingForRedemption not transition of BTC events
  test(
    "PeginTransitionRelation should not transition from WaitingForRedemption on BTC events"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForRedemption(
            currentTolpBlockHeight = 1L,
            currentWalletIdx = 0,
            scriptAsm = "",
            redeemAddress = redeemAddress,
            claimAddress = claimAddress,
            btcTxId = "txId",
            btcVout = 0L,
            utxoTxId = "bifrostTxId",
            utxoIndex = 0,
            amount = AssetToken("groupId", "seriesId", 100L)
          ),
          BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
        PeginTransitionRelation
          .handleBlockchainEvent[IO](
            WaitingForRedemption(
              currentTolpBlockHeight = 1L,
              currentWalletIdx = 0,
              scriptAsm = "",
              redeemAddress = redeemAddress,
              claimAddress = claimAddress,
              btcTxId = "txId",
              btcVout = 0L,
              utxoTxId = "bifrostTxId",
              utxoIndex = 0,
              amount = AssetToken("groupId", "seriesId", 100L)
            ),
            BTCFundsWithdrawn("txId", 0)
          )(transitionToEffect[IO](_, _))
          .isEmpty
    )
  }

  test(
    "PeginTransitionRelation should transition from WaitingForClaim to WaitingForClaimBTCConfirmation"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForClaim(
            someStartBtcBlockHeight =
              None, // Assuming None if not specified, adjust as necessary
            secret = "yourSecretHere", // Replace with actual secret
            currentWalletIdx = 0, // Adjust according to your logic
            btcTxId =
              "yourBtcTxIdHere", // Replace with actual BTC transaction ID
            btcVout = 0L, // Adjust as necessary
            scriptAsm = "yourScriptAsmHere", // Replace with actual script ASM
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ), // Adjust amount as necessary
            claimAddress = claimAddress
          ),
          BTCFundsDeposited(2, claimAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[WaitingForClaimBTCConfirmation]
        &&
          PeginTransitionRelation
            .handleBlockchainEvent[IO](
              WaitingForClaim(
                someStartBtcBlockHeight =
                  None, // Assuming None if not specified, adjust as necessary
                secret = "yourSecretHere", // Replace with actual secret
                currentWalletIdx = 0, // Adjust according to your logic
                btcTxId =
                  "yourBtcTxIdHere", // Replace with actual BTC transaction ID
                btcVout = 0L, // Adjust as necessary
                scriptAsm =
                  "yourScriptAsmHere", // Replace with actual script ASM
                amount = AssetToken(
                  "groupId",
                  "seriesId",
                  100L
                ), // Adjust amount as necessary
                claimAddress = claimAddress
              ),
              BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
            )(transitionToEffect[IO](_, _))
            .isEmpty
    )
  }

  // WaitingForClaimBTCConfirmation -> EndTransition when timeout
  test(
    "PeginTransitionRelation should transition from WaitingForClaimBTCConfirmation to EndTransition when timeout"
  ) {
    assert(
      (PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForClaimBTCConfirmation(
            1,
            "secret",
            1,
            "btcTxId",
            0,
            "scriptAsm",
            AssetToken("groupId", "seriesId", 100L),
            claimAddress
          ),
          NewBTCBlock(8)
        )(transitionToEffect[IO](_, _))
        .get
        .isInstanceOf[EndTransition[IO]]: @nowarn) &&
        PeginTransitionRelation
          .handleBlockchainEvent[IO](
            WaitingForClaimBTCConfirmation(
              1,
              "secret",
              1,
              "btcTxId",
              0,
              "scriptAsm",
              AssetToken("groupId", "seriesId", 100L),
              claimAddress
            ),
            NewBTCBlock(7)
          )(transitionToEffect[IO](_, _))
          .isEmpty
    )
  }

  test(
    "PeginTransitionRelation should not transition from WaitingForClaim to EndTransition when the address is different"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForClaim(
            someStartBtcBlockHeight =
              None, // Assuming None if not specified, adjust as necessary
            secret = "yourSecretHere", // Replace with actual secret
            currentWalletIdx = 0, // Adjust according to your logic
            btcTxId =
              "yourBtcTxIdHere", // Replace with actual BTC transaction ID
            btcVout = 0L, // Adjust as necessary
            scriptAsm = "yourScriptAsmHere", // Replace with actual script ASM
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ), // Adjust amount as necessary
            claimAddress = claimAddress
          ),
          BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  // WaitingForClaim not transition on Bifrost events
  test(
    "PeginTransitionRelation should not transition from WaitingForClaim on Bifrost events"
  ) {
    import co.topl.brambl.syntax._
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForClaim(
            someStartBtcBlockHeight =
              None, // Assuming None if not specified, adjust as necessary
            secret = "yourSecretHere", // Replace with actual secret
            currentWalletIdx = 0, // Adjust according to your logic
            btcTxId =
              "yourBtcTxIdHere", // Replace with actual BTC transaction ID
            btcVout = 0L, // Adjust as necessary
            scriptAsm = "yourScriptAsmHere", // Replace with actual script ASM
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ), // Adjust amount as necessary
            claimAddress = claimAddress
          ),
          BifrostFundsDeposited(
            currentToplBlockHeight =
              0L, // Assuming a missing parameter needs to be added
            address = redeemAddress,
            utxoTxId = "utxoTxId",
            utxoIndex = 0,
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ) // Assuming AssetToken is a valid BifrostCurrencyUnit
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
        PeginTransitionRelation
          .handleBlockchainEvent[IO](
            WaitingForClaim(
              someStartBtcBlockHeight =
                None, // Assuming None if not specified, adjust as necessary
              secret = "yourSecretHere", // Replace with actual secret
              currentWalletIdx = 0, // Adjust according to your logic
              btcTxId =
                "yourBtcTxIdHere", // Replace with actual BTC transaction ID
              btcVout = 0L, // Adjust as necessary
              scriptAsm = "yourScriptAsmHere", // Replace with actual script ASM
              amount = AssetToken(
                "groupId",
                "seriesId",
                100L
              ), // Adjust amount as necessary
              claimAddress = claimAddress
            ),
            BifrostFundsWithdrawn(
              "bifrostTxId",
              0,
              "topl-secret",
              AssetToken("groupId", "seriesId", 100L)
            )
          )(transitionToEffect[IO](_, _))
          .isEmpty
    )
  }

  // MintingTBTC -> EndTransition when timeout
  test(
    "PeginTransitionRelation should transition from MintingTBTC to EndTransition when timeout"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          MintingTBTC(
            1,
            1,
            "",
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100
          ),
          NewBTCBlock(102)
        )(transitionToEffect[IO](_, _))
        .get
        .isInstanceOf[EndTransition[IO]]: @nowarn
    )
  }

  // MintingTBTC -> WaitingForRedemption
  test(
    "PeginTransitionRelation should transition from MintingTBTC to WaitingForRedemption"
  ) {

    import co.topl.brambl.syntax._
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          MintingTBTC(
            1,
            1,
            "",
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100
          ),
          BifrostFundsDeposited(
            currentToplBlockHeight =
              0L, // Assuming a missing parameter needs to be added
            address = redeemAddress,
            utxoTxId = "utxoTxId",
            utxoIndex = 0,
            amount = AssetToken(
              Encoding.encodeToBase58(groupId.value.toByteArray),
              Encoding.encodeToBase58(seriesId.value.toByteArray),
              100L
            ) // Assuming AssetToken is a valid BifrostCurrencyUnit
          )
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[WaitingForRedemption]
    )
  }

  // MintingTBTC -> WaitingForRedemption not transition
  test(
    "PeginTransitionRelation should not transition from MintingTBTC to WaitingForRedemption"
  ) {
    import co.topl.brambl.syntax._
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          MintingTBTC(
            1,
            1,
            "",
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100
          ),
          BifrostFundsDeposited(
            currentToplBlockHeight =
              0L, // Assuming a missing parameter needs to be added
            address = redeemAddressOther,
            utxoTxId = "utxoTxId",
            utxoIndex = 0,
            amount = AssetToken(
              "groupId",
              "seriesId",
              100L
            ) // Assuming AssetToken is a valid BifrostCurrencyUnit
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty
    )
  }

  // MintingTBTC not transition on BTC events
  test(
    "PeginTransitionRelation should not transition from MintingTBTC on BTC events"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          MintingTBTC(
            1,
            1,
            "",
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100
          ),
          BTCFundsDeposited(2, escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
        PeginTransitionRelation
          .handleBlockchainEvent[IO](
            MintingTBTC(
              1,
              1,
              "",
              redeemAddress,
              claimAddress,
              "btcTxId",
              0,
              100
            ),
            BTCFundsWithdrawn("txId", 0)
          )(transitionToEffect[IO](_, _))
          .isEmpty
    )
  }

  // WaitingForEscrowBTCConfirmation -> MintingTBTC
  test(
    "PeginTransitionRelation should transition from WaitingForEscrowBTCConfirmation to MintingTBTC"
  ) {
            println (PeginTransitionRelation
          .handleBlockchainEvent[IO](
            WaitingForEscrowBTCConfirmation(
              1,
              1,
              1,
              "",
              escrowAddress,
              redeemAddress,
              claimAddress,
              "btcTxId",
              0,
              100
            ),
            NewBTCBlock(7)
          )(transitionToEffect[IO](_, _))
          )
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForEscrowBTCConfirmation(
            1,
            1,
            1,
            "",
            escrowAddress,
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100
          ),
          NewBTCBlock(8)
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[MintingTBTC]
    )
  }
  // WaitingForEscrowBTCConfirmation -> WaitingForBTC
  test(
    "PeginTransitionRelation should transition from WaitingForEscrowBTCConfirmation to WaitingForBTC on reorg"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForEscrowBTCConfirmation(
            1,
            8,
            1,
            "",
            escrowAddress,
            redeemAddress,
            claimAddress,
            "btcTxId",
            0,
            100
          ),
          NewBTCBlock(8)
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[WaitingForBTC]
    )
  }

}
