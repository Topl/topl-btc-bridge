package co.topl.bridge.statemachine.pegin

import cats.effect.IO
import cats.effect.kernel.Async
import co.topl.bridge.AssetToken
import munit.CatsEffectSuite
import org.bitcoins.core.protocol.Bech32Address
import scala.annotation.nowarn
import co.topl.bridge.controllers.SharedData

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
    "PeginTransitionRelation should go from WaitingForBTC to MintingTBTC on deposited funds"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForBTC(1L, 1, "", escrowAddress, redeemAddress, claimAddress),
          BTCFundsDeposited(escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[MintingTBTC] && PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForBTC(1, 1, "", escrowAddress, redeemAddress, claimAddress),
          BTCFundsDeposited(escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .get
        .asInstanceOf[FSMTransitionTo[IO]]
        .nextState
        .isInstanceOf[MintingTBTC]
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
          BTCFundsDeposited(escrowAddressPubkey, "txId", 0, 100.satoshis)
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
        .isInstanceOf[EndTrasition[IO]]: @nowarn)
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
            redeemAddress,
            "utxoTxId",
            0,
            AssetToken("groupId", "seriesId", 100L)
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
    import co.topl.brambl.syntax._
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForRedemption(
            1,
            "",
            redeemAddress,
            claimAddress,
            "txId",
            0,
            "bifrostTxId",
            0
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
    "PeginTransitionRelation should NOT transition from WaitingForRedemption to BifrostFundsWithdrawn if guard fails"
  ) {
    import co.topl.brambl.syntax._
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForRedemption(
            1,
            "",
            redeemAddress,
            claimAddress,
            "txId",
            0,
            "bifrostTxId",
            0
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
              1,
              "",
              redeemAddress,
              claimAddress,
              "txId",
              0,
              "bifrostTxId",
              0
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
            1,
            "",
            redeemAddress,
            claimAddress,
            "txId",
            0,
            "bifrostTxId",
            0
          ),
          BTCFundsDeposited(escrowAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
        PeginTransitionRelation
          .handleBlockchainEvent[IO](
            WaitingForRedemption(
              1,
              "",
              redeemAddress,
              claimAddress,
              "txId",
              0,
              "bifrostTxId",
              0
            ),
            BTCFundsWithdrawn("txId", 0)
          )(transitionToEffect[IO](_, _))
          .isEmpty
    )
  }

  test(
    "PeginTransitionRelation should transition from WaitingForClaim to EndTrasition"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForClaim(claimAddress),
          BTCFundsDeposited(claimAddressPubkey, "txId", 0, 100.satoshis)
        )(transitionToEffect[IO](_, _))
        .get
        .isInstanceOf[EndTrasition[IO]]: @nowarn
    )
  }

  test(
    "PeginTransitionRelation should not transition from WaitingForClaim to EndTrasition when the address is different"
  ) {
    assert(
      PeginTransitionRelation
        .handleBlockchainEvent[IO](
          WaitingForClaim(claimAddress),
          BTCFundsDeposited(escrowAddressPubkey, "txId", 0, 100.satoshis)
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
          WaitingForClaim(claimAddress),
          BifrostFundsDeposited(
            redeemAddress,
            "utxoTxId",
            0,
            AssetToken("groupId", "seriesId", 100L)
          )
        )(transitionToEffect[IO](_, _))
        .isEmpty &&
        PeginTransitionRelation
          .handleBlockchainEvent[IO](
            WaitingForClaim(claimAddress),
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
            redeemAddress,
            "utxoTxId",
            0,
            AssetToken("groupId", "seriesId", 100L)
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
            redeemAddressOther,
            "utxoTxId",
            0,
            AssetToken("groupId", "seriesId", 100L)
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
          BTCFundsDeposited(escrowAddressPubkey, "txId", 0, 100.satoshis)
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

}
