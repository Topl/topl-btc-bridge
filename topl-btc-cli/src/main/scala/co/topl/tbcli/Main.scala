package co.topl.tbcli

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Sync
import cats.effect.std
import co.topl.shared.BitcoinNetworkIdentifiers
import co.topl.shared.StartSessionRequest
import co.topl.shared.utils.KeyGenerationUtils
import scopt.OParser

import java.security.MessageDigest

object Main extends IOApp with TBCLIParamsDescriptor {

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
      startSessionRequest: StartSessionRequest
  ): F[Unit] = {
    import cats.implicits._
    import co.topl.tbcli.view.OutputView._
    std.Console[F].println(startSessionRequest.show)
  }

  def processInitSession[F[_]: Sync](
      btcNetwork: BitcoinNetworkIdentifiers,
      initSession: InitSession
  ): F[StartSessionRequest] = {
    import cats.implicits._
    for {
      secretSha256 <- Sync[F].delay(
        MessageDigest
          .getInstance("SHA-256")
          .digest(initSession.secret.getBytes("UTF-8"))
          .map("%02x".format(_))
          .mkString
      )
      pKey <- KeyGenerationUtils
        .generateKey[F](btcNetwork, initSession.seedFile, initSession.password)
    } yield StartSessionRequest(pKey, secretSha256)
  }
}
