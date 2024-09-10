package co.topl.bridge.consensus.core.pbft

import cats.effect.IO
import cats.effect.kernel.Ref
import co.topl.brambl.builders.TransactionBuilderApi
import co.topl.brambl.cli.mockbase.BaseTransactionBuilderApi
import co.topl.brambl.cli.mockbase.BaseWalletStateAlgebra
import co.topl.brambl.dataApi.FellowshipStorageAlgebra
import co.topl.brambl.dataApi.GenusQueryAlgebra
import co.topl.brambl.dataApi.TemplateStorageAlgebra
import co.topl.brambl.dataApi.WalletStateAlgebra
import co.topl.brambl.monitoring.BitcoinMonitor
import co.topl.bridge.consensus.core.BridgeWalletManager
import co.topl.bridge.consensus.core.PeginWalletManager
import co.topl.bridge.consensus.core.PublicApiClientGrpcMap
import co.topl.bridge.consensus.core.channelResource
import co.topl.bridge.consensus.pbft.CheckpointRequest
import co.topl.bridge.consensus.pbft.PBFTInternalServiceFs2Grpc
import co.topl.bridge.consensus.subsystems.monitor.SessionManagerAlgebra
import co.topl.bridge.stubs.BaseBTCWalletAlgebra
import co.topl.bridge.stubs.BaseFellowshipStorageAlgebra
import co.topl.bridge.stubs.BaseGenusQueryAlgebra
import co.topl.bridge.stubs.BaseLogger
import co.topl.bridge.stubs.BasePBFTInternalGrpcServiceClient
import co.topl.bridge.stubs.BaseSessionManagerAlgebra
import co.topl.bridge.stubs.BaseStorageApi
import co.topl.bridge.stubs.BaseTemplateStorageAlgebra
import com.google.protobuf.ByteString
import io.grpc.Metadata
import munit.CatsEffectSuite
import org.bitcoins.rpc.config.BitcoindAuthCredentials
import org.typelevel.log4cats.Logger
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
import fs2.io.process

class PBFTInternalGrpcServiceServerSpec
    extends CatsEffectSuite
    with PBFTInternalGrpcServiceServerSpecAux {

  override def afterAll(): Unit = {
    import cats.implicits._
    (0 to 6).toList
      .map(idx =>
        process
          .ProcessBuilder(
            "rm",
            Seq(
              s"privateKey${idx}.pem",
              s"publicKey${idx}.pem"
            ): _*
          )
          .spawn[IO]
          .use(p =>
            p.stdout
              .through(fs2.text.utf8.decode)
              .compile
              .foldMonoid
              .map(_.trim) >> IO.unit
          )
      )
      .sequence
      .unsafeRunSync()
  }

  override def beforeAll() = {
    import cats.implicits._
    Security.addProvider(new BouncyCastleProvider());
    (for {
      _ <- (0 to 6).toList
        .map(idx =>
          process
            .ProcessBuilder(
              "rm",
              Seq(
                s"privateKey${idx}.pem",
                s"publicKey${idx}.pem"
              ): _*
            )
            .spawn[IO]
            .use(p =>
              p.stdout
                .through(fs2.text.utf8.decode)
                .compile
                .foldMonoid
                .map(_.trim) >> IO.unit
            )
        )
        .sequence
      _ <- (0 to 6).toList
        .map(idx =>
          process
            .ProcessBuilder(
              "openssl",
              Seq(
                "ecparam",
                "-name",
                "secp256k1",
                "-genkey",
                "-noout",
                "-out",
                s"privateKey${idx}.pem"
              ): _*
            )
            .spawn[IO]
            .use(p =>
              p.stdout
                .through(fs2.text.utf8.decode)
                .compile
                .foldMonoid
                .map(_.trim) >> IO.unit
            )
        )
        .sequence
      _ <- (0 to 6).toList
        .map(idx =>
          process
            .ProcessBuilder(
              "openssl",
              Seq(
                "ec",
                "-in",
                s"privateKey${idx}.pem",
                "-pubout",
                "-out",
                s"publicKey${idx}.pem"
              ): _*
            )
            .spawn[IO]
            .use(p =>
              p.stdout
                .through(fs2.text.utf8.decode)
                .compile
                .foldMonoid
                .map(_.trim) >> IO.unit
            )
        )
        .sequence
    } yield ()).unsafeRunSync()
  }

  val setupServer =
    ResourceFunFixture[
      (PBFTInternalServiceFs2Grpc[IO, Metadata], Ref[IO, List[String]])
    ] {
      Security.addProvider(new BouncyCastleProvider());
      implicit val storageApiStub = new BaseStorageApi()
      val btcWalletAlgebraStub = new BaseBTCWalletAlgebra()
      implicit val peginWalletManager =
        new PeginWalletManager[IO](btcWalletAlgebraStub)
      implicit val bridgeWalletManager =
        new BridgeWalletManager[IO](btcWalletAlgebraStub)
      implicit val tba: TransactionBuilderApi[IO] =
        new BaseTransactionBuilderApi()
      implicit val wsa: WalletStateAlgebra[IO] = new BaseWalletStateAlgebra()
      implicit val utxoAlgebra: GenusQueryAlgebra[IO] =
        new BaseGenusQueryAlgebra()
      implicit val sessionManager: SessionManagerAlgebra[IO] =
        new BaseSessionManagerAlgebra()
      implicit val toplChannelResource = channelResource[IO](
        toplHost,
        toplPort,
        toplSecureConnection
      )
      val credentials = BitcoindAuthCredentials.PasswordBased(
        btcUser,
        btcPassword
      )
      implicit val bitcoindInstance = BitcoinMonitor.Bitcoind.remoteConnection(
        btcNetwork,
        btcUrl,
        credentials
      )
      implicit val fellowshipStorageAlgebra: FellowshipStorageAlgebra[IO] =
        new BaseFellowshipStorageAlgebra()
      implicit val publicApiClientGrpcMap = new PublicApiClientGrpcMap[IO](
        Map.empty
      )
      implicit val templateStorageAlgebra: TemplateStorageAlgebra[IO] =
        new BaseTemplateStorageAlgebra()
      import cats.implicits._

      (for {
        loggedError <- Ref.of[IO, List[String]](List.empty).toResource
      } yield {
        implicit val logger: Logger[IO] =
          new BaseLogger() {
            override def error(message: => String): IO[Unit] =
              loggedError.update(_ :+ message)
          }
        for {
          serverUnderTest <- createSimpleInternalServer(
            new BasePBFTInternalGrpcServiceClient()
          )
        } yield {
          (serverUnderTest, loggedError)
        }
      }).flatten
    }

  setupServer.test("checkpoint should check message signature") {
    serverAndLogChecker =>
      val (server, logChecker) = serverAndLogChecker
      assertIO(
        for {

          _ <- server.checkpoint(
            CheckpointRequest(
              sequenceNumber = 0L,
              digest = ByteString.EMPTY,
              replicaId = 0,
              signature = ByteString.EMPTY
            ),
            new Metadata()
          )
          errorMessage <- logChecker.get
        } yield errorMessage.head.contains("Signature verification failed"),
        true
      )
  }

}
