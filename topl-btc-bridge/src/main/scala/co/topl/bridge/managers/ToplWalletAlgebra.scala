package co.topl.bridge.managers

import cats.Monad
import cats.data.OptionT
import cats.effect.kernel.Sync
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.constants.NetworkConstants
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletFellowship
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.dataApi.WalletTemplate
import co.topl.brambl.models.LockAddress
import co.topl.brambl.models.LockId
import co.topl.brambl.models.box.AssetMintingStatement
import co.topl.brambl.models.transaction.IoTransaction
import co.topl.brambl.utils.Encoding
import co.topl.brambl.wallet.WalletApi
import co.topl.genus.services.Txo
import co.topl.shared.ToplNetworkIdentifiers
import com.google.protobuf.ByteString
import io.circe.Json
import quivr.models.KeyPair
import quivr.models.VerificationKey

trait ToplWalletAlgebra[F[_]] {

  def createSimpleAssetMintingTransactionFromParams(
      keyPair: KeyPair,
      fromFellowship: String,
      fromTemplate: String,
      someFromInteraction: Option[Int],
      fee: Long,
      ephemeralMetadata: Option[Json],
      commitment: Option[ByteString],
      assetMintingStatement: AssetMintingStatement
  ): F[IoTransaction]

  def setupBridgeWallet(
      networkId: ToplNetworkIdentifiers,
      keyPair: KeyPair,
      userBaseKey: String,
      sessionId: String,
      sha256: String,
      waitTime: Int,
      currentHeight: Int
  ): F[Option[String]]

}

object ToplWalletImpl {
  import cats.implicits._

  def make[F[_]](
      psync: Sync[F],
      walletApi: WalletApi[F],
      fellowshipStorageAlgebra: FellowshipStorageAlgebra[F],
      templateStorageAlgebra: TemplateStorageAlgebra[F],
      walletStateApi: WalletStateAlgebra[F],
      transactionBuilderApi: TransactionBuilderApi[F],
      utxoAlgebra: GenusQueryAlgebra[F]
  ): ToplWalletAlgebra[F] = new ToplWalletAlgebra[F]
    with WalletApiHelpers[F]
    with AssetMintingOps[F] {

    override implicit val sync: cats.effect.kernel.Sync[F] = psync

    implicit val m: Monad[F] = sync

    val wsa: WalletStateAlgebra[F] = walletStateApi

    val tba = transactionBuilderApi

    val wa = walletApi

    val templateName = "bridgeTemplate"

    private def computeSerializedTemplate(
        sha256: String,
        waitTime: Int,
        currentHeight: Int
    ) =
      for {
        decodedHex <- OptionT(
          Sync[F].delay(
            Encoding.decodeFromHex(sha256).toOption
          )
        )
        lockTemplateAsJson <- OptionT(Sync[F].delay(s"""{
                "threshold":1,
                "innerTemplates":[
                  {
                    "left": {"routine":"ExtendedEd25519","entityIdx":0,"type":"signature"},
                    "right": {"chain":"header","min": ${currentHeight + waitTime + 1},"max":9223372036854775807,"type":"height"},
                    "type": "and"
                  },
                  {
                    "left": {"chain":"header","min": ${currentHeight},"max": ${currentHeight + waitTime},"type":"height"},
                    "right": {
                      "type": "and",
                      "left": {"routine":"ExtendedEd25519","entityIdx":1,"type":"signature"},
                      "right": {"routine":"Sha256","digest": "${Encoding
            .encodeToBase58(decodedHex)}","type":"digest"}
                    },
                    "type": "and"
                  }
                ],
                "type":"predicate"}
              """.some))
      } yield lockTemplateAsJson

    def setupBridgeWallet(
        networkId: ToplNetworkIdentifiers,
        keypair: KeyPair,
        userBaseKey: String,
        sessionId: String,
        sha256: String,
        waitTime: Int,
        currentHeight: Int
    ): F[Option[String]] = {

      import cats.implicits._
      import co.topl.brambl.common.ContainsEvidence.Ops
      import co.topl.brambl.common.ContainsImmutable.instances._
      import TransactionBuilderApi.implicits._
      implicit class ImplicitConversion[A](x: F[A]) {
        def optionT = OptionT(x.map(_.some))
      }
      implicit class ImplicitConversion1[A](x: F[Option[A]]) {
        def liftT = OptionT(x)
      }
      (for {
        _ <-
          fellowshipStorageAlgebra
            .addFellowship(
              WalletFellowship(0, sessionId)
            )
            .optionT
        lockTemplateAsJson <- computeSerializedTemplate(
          sha256,
          waitTime,
          currentHeight
        )
        _ <- templateStorageAlgebra
          .addTemplate(
            WalletTemplate(0, templateName, lockTemplateAsJson)
          )
          .optionT
        indices <- walletStateApi
          .getCurrentIndicesForFunds(
            sessionId,
            templateName,
            None
          )
          .liftT
        userVk <- walletApi
          .deriveChildVerificationKey(
            VerificationKey.parseFrom(
              Encoding.decodeFromBase58(userBaseKey).toOption.get
            ),
            1
          )
          .optionT
        lockTempl <- walletStateApi
          .getLockTemplate(templateName)
          .liftT
        deriveChildKeyUserString = Encoding.encodeToBase58(
          userVk.toByteArray
        )
        bridgeKey <- walletApi
          .deriveChildKeys(keypair, indices)
          .map(_.vk)
          .optionT
        deriveChildKeyBridgeString = Encoding.encodeToBase58(
          bridgeKey.toByteArray
        )
        lock <- lockTempl
          .build(
            userVk :: bridgeKey :: Nil
          )
          .map(_.toOption)
          .liftT
        lockAddress = LockAddress(
          networkId.networkId,
          NetworkConstants.MAIN_LEDGER_ID,
          LockId(lock.sizedEvidence.digest.value)
        )
        _ <- walletStateApi
          .updateWalletState(
            Encoding.encodeToBase58Check(
              lock.getPredicate.toByteArray
            ), // lockPredicate
            lockAddress.toBase58(), // lockAddress
            Some("ExtendedEd25519"),
            Some(deriveChildKeyBridgeString),
            indices
          )
          .optionT
        _ <- walletStateApi
          .addEntityVks(
            sessionId,
            templateName,
            deriveChildKeyUserString :: deriveChildKeyBridgeString :: Nil
          )
          .optionT
        currentAddress <- walletStateApi
          .getAddress(
            sessionId,
            templateName,
            None
          )
          .liftT
      } yield currentAddress).value
    }
    private def sharedOps(
        fromFellowship: String,
        fromTemplate: String,
        someFromInteraction: Option[Int]
    ) = for {
      someCurrentIndices <- getCurrentIndices(
        fromFellowship,
        fromTemplate,
        someFromInteraction
      )
      predicateFundsToUnlock <- getPredicateFundsToUnlock(someCurrentIndices)
      someNextIndices <- getNextIndices(fromFellowship, fromTemplate)
      changeLock <- getChangeLockPredicate(
        someNextIndices,
        fromFellowship,
        fromTemplate
      )
    } yield (
      predicateFundsToUnlock.get,
      someCurrentIndices,
      someNextIndices,
      changeLock
    )

    def createSimpleAssetMintingTransactionFromParams(
        keyPair: KeyPair,
        fromFellowship: String,
        fromTemplate: String,
        someFromInteraction: Option[Int],
        fee: Long,
        ephemeralMetadata: Option[Json],
        commitment: Option[ByteString],
        assetMintingStatement: AssetMintingStatement
    ): F[IoTransaction] = for {
      tuple <- sharedOps(
        fromFellowship,
        fromTemplate,
        someFromInteraction
      )
      (
        predicateFundsToUnlock,
        someCurrentIndices,
        someNextIndices,
        changeLock
      ) = tuple
      fromAddress <- transactionBuilderApi.lockAddress(
        predicateFundsToUnlock
      )
      response <- utxoAlgebra
        .queryUtxo(fromAddress)
        .attempt
        .flatMap {
          _ match {
            case Left(_) =>
              Sync[F].raiseError(
                CreateTxError("Problem contacting network")
              ): F[Seq[Txo]]
            case Right(txos) => Sync[F].pure(txos: Seq[Txo])
          }
        }
      lvlTxos = response.filter(
        _.transactionOutput.value.value.isLvl
      )
      nonLvlTxos = response.filter(x =>
        (
          !x.transactionOutput.value.value.isLvl &&
            x.outputAddress != assetMintingStatement.groupTokenUtxo &&
            x.outputAddress != assetMintingStatement.seriesTokenUtxo
        )
      )
      groupTxo <- response
        .filter(
          _.transactionOutput.value.value.isGroup
        )
        .find(_.outputAddress == assetMintingStatement.groupTokenUtxo)
        .map(Sync[F].delay(_))
        .getOrElse(
          Sync[F].raiseError(
            new Exception(
              "Group token utxo not found"
            )
          )
        )
      seriesTxo <- response
        .filter(
          _.transactionOutput.value.value.isSeries
        )
        .find(_.outputAddress == assetMintingStatement.seriesTokenUtxo)
        .map(Sync[F].delay(_))
        .getOrElse(
          Sync[F].raiseError(
            new Exception(
              "Series token utxo not found"
            )
          )
        )
      ioTransaction <- buildAssetTxAux(
        keyPair,
        lvlTxos,
        nonLvlTxos,
        groupTxo,
        seriesTxo,
        fromAddress,
        predicateFundsToUnlock.getPredicate,
        fee,
        someNextIndices,
        assetMintingStatement,
        ephemeralMetadata,
        commitment,
        changeLock
      )
    } yield ioTransaction
  }

}
