package co.topl.bridge.managers

import cats.effect.kernel.Resource
import cats.effect.kernel.Sync
import co.topl.brambl.Context
import co.topl.brambl.dataApi.BifrostQueryAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.models.Datum
import co.topl.brambl.models.Event
import co.topl.brambl.models.transaction.IoTransaction
import co.topl.brambl.syntax.cryptoToPbKeyPair
import co.topl.brambl.utils.Encoding
import co.topl.brambl.validation.TransactionAuthorizationError
import co.topl.brambl.validation.TransactionSyntaxError
import co.topl.brambl.validation.TransactionSyntaxError.EmptyInputs
import co.topl.brambl.validation.TransactionSyntaxError.InvalidDataLength
import co.topl.brambl.validation.TransactionSyntaxInterpreter
import co.topl.brambl.wallet.CredentiallerInterpreter
import co.topl.brambl.wallet.WalletApi
import co.topl.crypto.signing.ExtendedEd25519
import co.topl.quivr.runtime.QuivrRuntimeError
import co.topl.quivr.runtime.QuivrRuntimeErrors
import io.grpc.ManagedChannel
import quivr.models.KeyPair

trait TransactionAlgebra[F[_]] {
  def proveSimpleTransactionFromParams(
      ioTransaction: IoTransaction,
      keyPair: KeyPair
  ): F[Either[SimpleTransactionAlgebraError, IoTransaction]]

  def broadcastSimpleTransactionFromParams(
      provedTransaction: IoTransaction
  ): F[Either[SimpleTransactionAlgebraError, String]]

}

object TransactionAlgebra {

  def make[F[_]: Sync](
      walletApi: WalletApi[F],
      walletStateApi: WalletStateAlgebra[F],
      channelResource: Resource[F, ManagedChannel]
  ) =
    new TransactionAlgebra[F] {

      private def quivrErrorToString(qre: QuivrRuntimeError): String =
        qre match {
          case QuivrRuntimeErrors.ValidationError
                .EvaluationAuthorizationFailed(_, _) =>
            "Evaluation authorization failed"
          case QuivrRuntimeErrors.ValidationError.MessageAuthorizationFailed(
                _
              ) =>
            "Message authorization failed"
          case QuivrRuntimeErrors.ValidationError.LockedPropositionIsUnsatisfiable =>
            "Locked proposition is unsatisfiable"
          case QuivrRuntimeErrors.ValidationError.UserProvidedInterfaceFailure =>
            "User provided interface failure"
          case _: QuivrRuntimeError => "Unknown error: " + qre.toString
        }

      override def broadcastSimpleTransactionFromParams(
          provedTransaction: IoTransaction
      ): F[Either[SimpleTransactionAlgebraError, String]] = {
        import cats.implicits._
        (for {
          _ <- validateTx(provedTransaction)
          validations <- checkSignatures(provedTransaction)
          _ <- Sync[F]
            .raiseError(
              new IllegalStateException(
                "Error validating transaction: " + validations
                  .map(_ match {
                    case TransactionAuthorizationError
                          .AuthorizationFailed(errors) =>
                      errors.map(quivrErrorToString).mkString(", ")
                    case _ =>
                      "Contextual or permanent error was found."
                  })
                  .mkString(", ")
              )
            )
            .whenA(validations.nonEmpty)
          response <- BifrostQueryAlgebra
            .make[F](channelResource)
            .broadcastTransaction(provedTransaction)
            .map(_ => provedTransaction)
            .adaptErr { e =>
              e.printStackTrace()
              NetworkProblem("Problem connecting to node")
            }
        } yield response).attempt.map(e =>
          e match {
            case Right(tx) =>
              import co.topl.brambl.syntax._
              Encoding.encodeToBase58(tx.id.value.toByteArray()).asRight
            case Left(e: SimpleTransactionAlgebraError) => e.asLeft
            case Left(e) => UnexpectedError(e.getMessage()).asLeft
          }
        )
      }

      private def checkSignatures(tx: IoTransaction) = {
        import cats.implicits._
        val mockKeyPair: KeyPair = (new ExtendedEd25519).deriveKeyPairFromSeed(
          Array.fill(96)(0: Byte)
        )
        for {
          credentialer <- Sync[F].delay(
            CredentiallerInterpreter
              .make[F](
                walletApi,
                walletStateApi,
                mockKeyPair
              )
          )
          tipBlockHeader <- BifrostQueryAlgebra
            .make[F](channelResource)
            .blockByDepth(1L)
            .map(_.get._2)
            .adaptErr { e =>
              e.printStackTrace()
              NetworkProblem("Problem connecting to node to get context")
            }
          context <- Sync[F].delay(
            Context[F](
              tx,
              tipBlockHeader.slot,
              Map(
                "header" -> Datum().withHeader(
                  Datum.Header(Event.Header(tipBlockHeader.height))
                )
              ).lift
            )
          )
          validationErrors <- credentialer.validate(tx, context)
        } yield validationErrors
      }

      private def validateTx(tx: IoTransaction) = {
        import cats.implicits._
        for {
          syntaxValidator <- Sync[F]
            .delay(
              TransactionSyntaxInterpreter
                .make[F]()
            )
          valResult <- syntaxValidator.validate(tx)
          _ <- valResult match {
            case Left(errors) =>
              Sync[F].raiseError(
                ValidateTxErrpr(
                  "Error validating transaction: " + errors
                    .map(_ match {
                      case InvalidDataLength =>
                        "Invalid data length. Transaction too big."
                      case EmptyInputs =>
                        "No inputs in transaction."
                      case TransactionSyntaxError.DuplicateInput(_) =>
                        "There are duplicate inputs in the transactions."
                      case TransactionSyntaxError.ExcessiveOutputsCount =>
                        "Too many outputs in the transaction."
                      case TransactionSyntaxError.InvalidTimestamp(_) =>
                        "The timestamp for the transaction is invalid."
                      case TransactionSyntaxError.InvalidSchedule(_) =>
                        "The schedule for the transaction is invalid."
                      case TransactionSyntaxError.NonPositiveOutputValue(_) =>
                        "One of the output values is not positive."
                      case TransactionSyntaxError
                            .InsufficientInputFunds(_, _) =>
                        "There are not enought funds to complete the transaction."
                      case TransactionSyntaxError.InvalidProofType(_, _) =>
                        "The type of the proof is invalid."
                      case TransactionSyntaxError.InvalidUpdateProposal(_) =>
                        "There are invalid update proposals in the output."
                      case _ =>
                        "Error."
                    })
                    .toList
                    .mkString(", ")
                )
              )
            case Right(_) => Sync[F].unit
          }
        } yield ()
      }

      override def proveSimpleTransactionFromParams(
          ioTransaction: IoTransaction,
          keyPair: KeyPair
      ): F[Either[SimpleTransactionAlgebraError, IoTransaction]] = {
        import cats.implicits._

        (for {
          credentialer <- Sync[F]
            .delay(
              CredentiallerInterpreter
                .make[F](walletApi, walletStateApi, keyPair)
            )
          provedTransaction <- credentialer.prove(ioTransaction)
          _ <- validateTx(ioTransaction)
        } yield provedTransaction).attempt.map(e =>
          e match {
            case Right(v)                               => v.asRight
            case Left(e: SimpleTransactionAlgebraError) => e.asLeft
            case Left(e) => UnexpectedError(e.getMessage()).asLeft
          }
        )
      }

    }
}
