package co.topl.tbcli

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import org.bitcoins.core.crypto.MnemonicCode
import org.bitcoins.core.hd.BIP32Path
import org.bitcoins.core.hd.HDAccount
import org.bitcoins.core.hd.HDPurposes
import org.bitcoins.core.wallet.keymanagement.KeyManagerParams
import org.bitcoins.crypto.AesPassword
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import scopt.OParser
import cats.effect.kernel.Sync
import cats.effect.std
import java.security.MessageDigest
import co.topl.tbcli.view.OutputInitSession

object Main extends IOApp {

  implicit val networkRead: scopt.Read[BitcoinNetworkIdentifiers] =
    scopt.Read
      .reads(BitcoinNetworkIdentifiers.fromString(_))
      .map(_ match {
        case Some(value) => value
        case None =>
          throw new IllegalArgumentException(
            "Invalid network. Possible values: mainnet, testnet, regtest"
          )
      })

  val builder = OParser.builder[ToplBTCCLIParamConfig]

  val parser = {
    import builder._
    import monocle.Optional
    val someCommand = Optional[ToplBTCCLIParamConfig, ToplBTCCLICommand] {
      _.command
    } { command =>
      _.copy(command = Some(command))
    }
    import monocle.macros.GenPrism
    val initSession = GenPrism[ToplBTCCLICommand, InitSession]
    import monocle.macros.GenLens
    val initSessionLens = someCommand.andThen(initSession)
    OParser.sequence(
      programName("tbcli"),
      head("tbcli", "0.1"),
      opt[BitcoinNetworkIdentifiers]('n', "network")
        .action((x, c) => c.copy(btcNetwork = x))
        .text(
          "Network name: Possible values: mainnet, testnet, regtest. (mandatory)"
        ),
      cmd("init-session")
        .action((_, c) => someCommand.replace(InitSession())(c))
        .text("Initialize a new session")
        .children(
          opt[String]("seed-file")
            .action((x, c) => {
              val seedFile = GenLens[InitSession](_.seedFile)
              initSessionLens
                .andThen(seedFile)
                .replace(x.trim())(c)
            })
            .validate(x =>
              if (!x.trim().isEmpty())
                if (new java.io.File(x.trim()).exists())
                  failure("Seed file already exists")
                else
                  success
              else failure("Seed file cannot be empty")
            )
            .text("Path to the seed file")
            .required(),
          opt[String]("secret")
            .action((x, c) => {
              val secret = GenLens[InitSession](_.secret)
              initSessionLens
                .andThen(secret)
                .replace(x.trim())(c)
            })
            .validate(x =>
              if (x.trim().isEmpty()) failure("Secret cannot be empty")
              else success
            )
            .text("Secret to initialize the session with"),
          opt[String]("password")
            .action((x, c) => {
              val password = GenLens[InitSession](_.password)
              initSessionLens
                .andThen(password)
                .replace(x.trim())(c)
            })
            .validate(x =>
              if (x.trim().isEmpty()) failure("Password cannot be empty")
              else success
            )
            .text("Secret to initialize the session with")
            .required()
        )
    )
  }

  override def run(args: List[String]): IO[ExitCode] = {
    OParser.parse(parser, args, ToplBTCCLIParamConfig()) match {
      case Some(config) =>
        config.command match {
          case Some(command) =>
            processCommand(config.btcNetwork, command)
          case None =>
            println("No command specified")
            IO(ExitCode.Error)
        }
      case None =>
        println("Invalid arguments")
        IO(ExitCode.Error)
    }
  }

  def processCommand(
      network: BitcoinNetworkIdentifiers,
      command: ToplBTCCLICommand
  ): IO[ExitCode] = command match {
    case e: InitSession =>
      for {
        res <- processInitSession[IO](network, e)
        _ <- displayInitSession[IO](res)
      } yield ExitCode.Success
  }

  def displayInitSession[F[_]: std.Console](
      outputInitSession: OutputInitSession
  ): F[Unit] = {
    import cats.implicits._
    import co.topl.tbcli.view.OutputView._
    std.Console[F].println(outputInitSession.show)
  }

  def generateKey[F[_]: Sync](
      btcNetwork: BitcoinNetworkIdentifiers,
      initSession: InitSession
  ): F[String] = {
    import cats.implicits._
    for {
      seedPath <- Sync[F].delay(
        new java.io.File(initSession.seedFile).getAbsoluteFile.toPath
      )
      purpose = HDPurposes.SegWit
      kmParams = KeyManagerParams(seedPath, purpose, btcNetwork.btcNetwork)
      aesPasswordOpt = Some(AesPassword.fromString(initSession.password))
      entropy = MnemonicCode.getEntropy256Bits
      mnemonic = MnemonicCode.fromEntropy(entropy)
      km <- Sync[F].fromEither(
        BIP39KeyManager.initializeWithMnemonic(
          aesPasswordOpt,
          mnemonic,
          None,
          kmParams
        )
      )
      hdAccount <- Sync[F].fromOption(
        HDAccount.fromPath(
          BIP32Path.fromString("m/84'/1'/0'")
        ) // this is the standard account path for segwit
        ,
        new IllegalArgumentException("Invalid account path")
      )
      pKey <- Sync[F].delay(
        km.deriveXPub(hdAccount)
          .get
          .deriveChildPubKey(BIP32Path.fromString("m/0/0"))
          .get
          .key
          .hex
      )
    } yield pKey
  }

  def processInitSession[F[_]: Sync](
      btcNetwork: BitcoinNetworkIdentifiers,
      initSession: InitSession
  ): F[OutputInitSession] = {
    import cats.implicits._
    for {
      secretSha256 <- Sync[F].delay(
        MessageDigest
          .getInstance("SHA-256")
          .digest(initSession.secret.getBytes("UTF-8"))
          .map("%02x".format(_))
          .mkString
      )
      pKey <- generateKey[F](btcNetwork, initSession)
    } yield OutputInitSession(pKey, secretSha256)
  }
}
