package wiggly.gin.ui

import cats.effect.IO
import tyrian.Cmd
import wiggly.gin.core.domain.Move
import wiggly.gin.core.port.Credentials

object Api {
  def create: Cmd[IO, Msg]                              = Cmd.None
  def join(id: String): Cmd[IO, Msg]                    = Cmd.None
  def play(seat: Credentials, move: Move): Cmd[IO, Msg] = Cmd.None
}
