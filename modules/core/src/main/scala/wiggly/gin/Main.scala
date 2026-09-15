package wiggly.gin

import cats.effect.{IO, IOApp}

object Main extends IOApp.Simple {
  val run: IO[Unit] = IO.println("wiggly-gin")
}
