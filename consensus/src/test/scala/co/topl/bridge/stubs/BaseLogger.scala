package co.topl.bridge.stubs

import org.typelevel.log4cats.Logger
import cats.effect.IO

class BaseLogger extends Logger[IO] {

  override def error(message: => String): IO[Unit] = ???

  override def warn(message: => String): IO[Unit] = ???

  override def info(message: => String): IO[Unit] = ???

  override def debug(message: => String): IO[Unit] = ???

  override def trace(message: => String): IO[Unit] = ???

  override def error(t: Throwable)(message: => String): IO[Unit] = ???

  override def warn(t: Throwable)(message: => String): IO[Unit] = ???

  override def info(t: Throwable)(message: => String): IO[Unit] = ???

  override def debug(t: Throwable)(message: => String): IO[Unit] = ???

  override def trace(t: Throwable)(message: => String): IO[Unit] = ???

}
