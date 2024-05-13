package co.topl.bridge.managers

import cats.Monad
import cats.data.OptionT
import cats.effect.kernel.Sync
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.codecs.AddressCodecs
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
import co.topl.shared.InvalidHash
import co.topl.shared.InvalidInput
import co.topl.shared.InvalidKey
import co.topl.shared.ToplNetworkIdentifiers
import com.google.protobuf.ByteString
import io.circe.Json
import quivr.models.KeyPair
import quivr.models.VerificationKey
import co.topl.bridge.Fellowship
import co.topl.bridge.Template
import co.topl.bridge.Lvl

trait ToplWalletAlgebra[+F[_]] {

  def createSimpleAssetMintingTransactionFromParams(
      keyPair: KeyPair,
      fromFellowship: Fellowship,
      fromTemplate: Template,
      someFromInteraction: Option[Int],
      fee: Lvl,
      ephemeralMetadata: Option[Json],
      commitment: Option[ByteString],
      assetMintingStatement: AssetMintingStatement,
      redeemLockAddress: String
  ): F[IoTransaction]

  def setupBridgeWallet(
      networkId: ToplNetworkIdentifiers,
      keyPair: KeyPair,
      userBaseKey: String,
      fellowshipName: String,
      templateName: String,
      sha256: String,
      waitTime: Int,
      currentHeight: Int
  ): F[Option[String]]

  def setupBridgeWalletForMinting(
      fromFellowship: String,
      mintTemplateName: String,
      keypair: KeyPair,
      sha256: String
  ): F[Option[(String, String)]]

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

    private def computeSerializedTemplateMintLock(
        sha256: String
    ) = {
      import cats.implicits._
      for {
        decodedHex <- OptionT(
          Encoding
            .decodeFromHex(sha256)
            .toOption
            .map(x => Sync[F].delay(x))
            .orElse(
              Some(
                Sync[F].raiseError[Array[Byte]](
                  new InvalidHash(
                    s"Invalid hash $sha256"
                  )
                )
              )
            )
            .sequence
        )
        lockTemplateAsJson <- OptionT(
          Sync[F].delay(templateFromSha(decodedHex).some)
        )
      } yield lockTemplateAsJson
    }

    def setupBridgeWalletForMinting(
        fromFellowship: String,
        mintTemplateName: String,
        keypair: KeyPair,
        sha256: String
    ): F[Option[(String, String)]] = {

      import cats.implicits._
      import TransactionBuilderApi.implicits._
      implicit class ImplicitConversion[A](x: F[A]) {
        def optionT = OptionT(x.map(_.some))
      }
      implicit class ImplicitConversion1[A](x: F[Option[A]]) {
        def liftT = OptionT(x)
      }
      (for {
        lockTemplateAsJson <- computeSerializedTemplateMintLock(
          sha256
        )
        _ <- fellowshipStorageAlgebra
          .addFellowship(
            WalletFellowship(0, fromFellowship)
          )
          .optionT
        _ <- templateStorageAlgebra
          .addTemplate(
            WalletTemplate(0, mintTemplateName, lockTemplateAsJson)
          )
          .optionT
        indices <- wsa
          .getNextIndicesForFunds(
            fromFellowship,
            mintTemplateName
          )
          .liftT
        bk <- wa
          .deriveChildKeysPartial(
            keypair,
            indices.x,
            indices.y
          )
          .optionT
        bridgeVk <- wa
          .deriveChildVerificationKey(
            bk.vk,
            1
          )
          .optionT
        lockTempl <- wsa
          .getLockTemplate(mintTemplateName)
          .liftT
        bridgePartialVk = Encoding.encodeToBase58(
          bk.vk.toByteArray
        )
        deriveChildKeyBridgeString = Encoding.encodeToBase58(
          bridgeVk.toByteArray
        )
        lock <- lockTempl
          .build(
            bridgeVk :: Nil
          )
          .map(_.toOption)
          .liftT
        lockAddress <- tba.lockAddress(lock).optionT
        _ <- wsa
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
        _ <- wsa
          .addEntityVks(
            fromFellowship,
            mintTemplateName,
            bridgePartialVk :: Nil
          )
          .optionT
        currentAddress <- wsa
          .getAddress(
            fromFellowship,
            mintTemplateName,
            None
          )
          .liftT
      } yield (currentAddress, bridgePartialVk)).value
    }

    private def computeSerializedTemplate(
        sha256: String,
        waitTime: Int,
        currentHeight: Int
    ) = {
      import cats.implicits._
      for {
        decodedHex <- OptionT(
          Encoding
            .decodeFromHex(sha256)
            .toOption
            .map(x => Sync[F].delay(x))
            .orElse(
              Some(
                Sync[F].raiseError[Array[Byte]](
                  new InvalidHash(
                    s"Invalid hash $sha256"
                  )
                )
              )
            )
            .sequence
        )
        _ <-
          if (currentHeight <= 0) {
            OptionT(
              Sync[F].raiseError[Option[Unit]](
                new InvalidInput(
                  s"Invalid block height $currentHeight"
                )
              )
            )
          } else {
            OptionT.some[F](())
          }
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
    }

    def setupBridgeWallet(
        networkId: ToplNetworkIdentifiers,
        keypair: KeyPair,
        userBaseKey: String,
        fellowshipName: String,
        templateName: String,
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
              WalletFellowship(0, fellowshipName)
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
            fellowshipName,
            templateName,
            None
          )
          .liftT
        bk <- Sync[F]
          .fromEither(Encoding.decodeFromBase58(userBaseKey))
          .handleErrorWith(_ =>
            Sync[F].raiseError(
              new InvalidKey(
                s"Invalid key $userBaseKey"
              )
            )
          )
          .optionT
        userVk <- walletApi
          .deriveChildVerificationKey(
            VerificationKey.parseFrom(
              bk
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
            fellowshipName,
            templateName,
            deriveChildKeyUserString :: deriveChildKeyBridgeString :: Nil
          )
          .optionT
        currentAddress <- walletStateApi
          .getAddress(
            fellowshipName,
            templateName,
            None
          )
          .liftT
      } yield currentAddress).value
    }
    private def sharedOps(
        fromFellowship: Fellowship,
        fromTemplate: Template,
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
        fromFellowship: Fellowship,
        fromTemplate: Template,
        someFromInteraction: Option[Int],
        fee: Lvl,
        ephemeralMetadata: Option[Json],
        commitment: Option[ByteString],
        assetMintingStatement: AssetMintingStatement,
        redeemLockAddress: String
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
        changeLock,
        AddressCodecs.decodeAddress(redeemLockAddress).toOption.get
      )
    } yield ioTransaction
  }

}
