package co.topl.bridge.managers

import cats.Monad
import cats.effect.kernel.Async
import cats.implicits._
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.codecs.AddressCodecs
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.LockAddress
import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.bridge.PeginSessionState
import co.topl.bridge.PeginSessionState.PeginSessionStateMintingTBTC
import co.topl.bridge.managers.PeginSessionInfo
import co.topl.bridge.managers.SessionManagerAlgebra
import co.topl.bridge.managers.ToplWalletAlgebra
import co.topl.bridge.managers.TransactionAlgebra
import co.topl.bridge.managers.WalletApiHelpers
import co.topl.genus.services.Txo
import com.google.protobuf.ByteString
import org.bitcoins.core.protocol.Bech32Address
import org.bitcoins.core.protocol.script.ScriptPubKey
import org.bitcoins.core.protocol.transaction.TransactionOutput
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.syntax._
import quivr.models.Int128
import quivr.models.KeyPair

import scala.concurrent.duration._
import co.topl.bridge.utils.MiscUtils

class WaitingBTCForBlock[F[_]: Async: Logger](
    sessionManager: SessionManagerAlgebra[F],
    walletStateApi: WalletStateAlgebra[F],
    transactionBuilderApi: TransactionBuilderApi[F]
) extends WalletApiHelpers[F] {

  val m: Monad[F] = implicitly[Monad[F]]

  val wsa: WalletStateAlgebra[F] = walletStateApi

  val tba: TransactionBuilderApi[F] = transactionBuilderApi

  private def getGroupTokeUtxo(txos: Seq[Txo]) = {
    txos
      .filter(_.transactionOutput.value.value.isGroup)
      .head
      .outputAddress
  }

  private def getSeriesTokenUtxo(txos: Seq[Txo]) = {
    txos
      .filter(_.transactionOutput.value.value.isSeries)
      .head
      .outputAddress
  }

  private def computeAssetMintingStatement(
      amount: Long,
      currentAddress: LockAddress,
      utxoAlgebra: GenusQueryAlgebra[F]
  ) = for {
    txos <- utxoAlgebra.queryUtxo(
      currentAddress
    )
  } yield AssetMintingStatement(
    getGroupTokeUtxo(txos),
    getSeriesTokenUtxo(txos),
    Int128(
      ByteString.copyFrom(BigInt(amount).toByteArray)
    )
  )

  private def mintTBTC(
      escrowAddress: String,
      fromFellowship: String,
      fromTemplate: String,
      assetMintingStatement: AssetMintingStatement,
      keyPair: KeyPair,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      transactionAlgebra: TransactionAlgebra[F],
      fee: Long
  ) = for {
    ioTransaction <- toplWalletAlgebra
      .createSimpleAssetMintingTransactionFromParams(
        keyPair,
        fromFellowship,
        fromTemplate,
        None,
        fee,
        None,
        None,
        assetMintingStatement,
        escrowAddress
      )
    provedIoTx <- transactionAlgebra
      .proveSimpleTransactionFromParams(
        ioTransaction,
        keyPair
      )
      .flatMap(Async[F].fromEither(_))
    txId <- transactionAlgebra
      .broadcastSimpleTransactionFromParams(provedIoTx)
      .flatMap(Async[F].fromEither(_))
  } yield txId

  private def getTxosFromMinting(
      genusQueryAlgebra: GenusQueryAlgebra[F],
      redeemAddress: String
  ): F[Seq[Txo]] = {
    for {
      txos <- genusQueryAlgebra.queryUtxo(
        AddressCodecs.decodeAddress(redeemAddress).toOption.get
      )
    } yield txos
  }

  private def checkIfAssetTokenMinted(
      sessionID: String,
      sessionInfo: PeginSessionInfo,
      sessionManager: SessionManagerAlgebra[F],
      genusQueryAlgebra: GenusQueryAlgebra[F]
  ): F[Unit] = for {
    newTxos <- getTxosFromMinting(genusQueryAlgebra, sessionInfo.redeemAddress)
    _ <-
      if (newTxos.filter(_.transactionOutput.value.value.isAsset).nonEmpty) {
        info"tBTC minted successfully to address ${sessionInfo.redeemAddress}" >>
          sessionManager.updateSession(
            sessionID,
            sessionInfo.copy(
              mintingBTCState =
                PeginSessionState.PeginSessionWaitingForRedemption,
              redeemAddress = sessionInfo.redeemAddress
            )
          )
      } else {
        info"tBTC not minted, checking txos in 5 seconds" >>
          Async[F].sleep(5.second) >> checkIfAssetTokenMinted(
            sessionID,
            sessionInfo,
            sessionManager,
            genusQueryAlgebra
          )
      }
  } yield ()

  private def startMintingProcess(
      fromFellowship: String,
      fromTemplate: String,
      escrowAddress: String,
      keyPair: KeyPair,
      amount: Long,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      transactionAlgebra: TransactionAlgebra[F],
      utxoAlgebra: GenusQueryAlgebra[F],
      fee: Long
  ): F[Unit] = {
    import cats.implicits._
    // import address codecs
    for {
      currentAddress <- getCurrentAddress(
        fromFellowship,
        fromTemplate,
        None
      )
      assetMintingStatement <- computeAssetMintingStatement(
        amount,
        currentAddress,
        utxoAlgebra
      )
      _ <- mintTBTC(
        escrowAddress,
        fromFellowship,
        fromTemplate,
        assetMintingStatement,
        keyPair,
        toplWalletAlgebra,
        transactionAlgebra,
        fee
      )
    } yield ()
  }

  def mintAndWait(
      peginSessionInfo: PeginSessionInfo,
      sessionId: String,
      toplKeypair: KeyPair,
      fromFellowship: String,
      fromTemplate: String,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      transactionAlgebra: TransactionAlgebra[F],
      utxoAlgebra: GenusQueryAlgebra[F],
      outputs: Seq[TransactionOutput]
  ) = for {
    _ <- sessionManager.updateSession(
      sessionId,
      peginSessionInfo.copy(mintingBTCState =
        PeginSessionStateMintingTBTC(
          outputs.map(_.value.satoshis.toLong).sum
        )
      )
    )
    _ <- info"Starting to mint tBTC for session $sessionId"
    _ <- startMintingProcess(
      fromFellowship,
      fromTemplate,
      peginSessionInfo.redeemAddress,
      toplKeypair,
      outputs.map(_.value.satoshis.toLong).sum,
      toplWalletAlgebra,
      transactionAlgebra,
      utxoAlgebra,
      10L
    )
    _ <-
      Async[F]
        .background(
          checkIfAssetTokenMinted(
            sessionId,
            peginSessionInfo,
            sessionManager,
            utxoAlgebra
          )
        )
        .allocated

  } yield ()

  def handleWaitingBTCForBlock(
      sessionId: String,
      toplKeypair: KeyPair,
      fromFellowship: String,
      fromTemplate: String,
      escrowAddress: String,
      toplWalletAlgebra: ToplWalletAlgebra[F],
      transactionAlgebra: TransactionAlgebra[F],
      utxoAlgebra: GenusQueryAlgebra[F],
      output: (ScriptPubKey, Seq[TransactionOutput])
  ) = {

    val (scriptPubkey, outputs) = output
    val bech32Address = Bech32Address.fromString(escrowAddress)
    if (scriptPubkey == bech32Address.scriptPubKey) {
      (for {
        sessionInfo <- sessionManager.getSession(sessionId)
        _ <- MiscUtils.sessionInfoPeginPrism
          .getOption(sessionInfo)
          .map(peginSessionInfo =>
            info"Starting minting process for session $sessionId" >>
              mintAndWait(
                peginSessionInfo,
                sessionId,
                toplKeypair,
                fromFellowship,
                fromTemplate,
                toplWalletAlgebra,
                transactionAlgebra,
                utxoAlgebra,
                outputs
              )
          )
          .getOrElse(Async[F].unit)
      } yield ())
    } else {
      Async[F].unit
    }
  }
}
