package wiggly.gin.core.service

import cats.Monad
import cats.effect.kernel.Clock
import cats.syntax.all.*
import fs2.Stream
import wiggly.gin.core.domain.*
import wiggly.gin.core.port.*

/** Drives the domain to satisfy [[GameService]], and the lobby board along with it: a waiting game
  * is listed while its host watches it.
  *
  * It holds the two things step 4 left for whoever holds a game: the ledger of a match and the
  * round being played. Every change goes through the store's own update, so the pair is written
  * once or not at all.
  */
object Games {

  def apply[F[_]: Monad: Clock](
      repository: GameRepository[F],
      events: GameEvents[F],
      board: LobbyBoard[F],
      shuffler: Shuffler[F],
      secrets: Secrets[F]
  ): GameService[F] = {
    new GameService[F] {

      def create(name: PlayerName): F[Credentials] = {
        for {
          id    <- secrets.gameId
          token <- secrets.token
          game = Game.AwaitingOpponent(id, token, name)
          _ <- repository.create(game)
          _ <- events.publish(game)
        } yield Credentials(id, Player.One, token)
      }

      def join(id: GameId, name: PlayerName): F[Either[GameFault, Credentials]] = {
        for {
          token  <- secrets.token
          deck   <- shuffler.shuffled
          joined <- repository.update(id)(_.joined(token, name, deck))
          _      <- announce(joined)
        } yield joined.as(Credentials(id, Player.Two, token))
      }

      def look(id: GameId, token: Token): F[Either[GameFault, PlayerView]] =
        seated(id, token).map(_.map((game, you) => PlayerView.of(game, you)))

      def play(id: GameId, token: Token, move: Move): F[Either[GameFault, PlayerView]] = {
        for {
          next  <- shuffler.shuffled
          moved <- repository.update(id) { game =>
            for {
              you    <- game.holder(token).toRight(GameFault.NotAPlayer)
              played <- game.played(you, move, next)
            } yield played
          }
          _ <- announce(moved)
        } yield moved.flatMap(seat(_, token)).map((game, you) => PlayerView.of(game, you))
      }

      def watch(id: GameId, token: Token): F[Either[GameFault, Stream[F, PlayerView]]] =
        seated(id, token).map(_.map { (game, you) =>
          val games = game match {
            case waiting: Game.AwaitingOpponent => listed(waiting, events.watch(id))
            case _                              => events.watch(id)
          }

          games.map(PlayerView.of(_, you))
        })

      /** The host's stream, holding the game in the lobby while it is open.
        *
        * Only the host can be seated at a waiting game, so this is the host's stream without
        * asking. A join can land before the row is made. The stream then opens on the game in
        * play, and the tap takes the row straight back down.
        */
      private def listed(
          waiting: Game.AwaitingOpponent,
          games: Stream[F, Game]
      ): Stream[F, Game] = {
        val open = Clock[F].realTime.flatMap { now =>
          board.change(_.opened(waiting.id, waiting.hostName, ListedAt(now.toMillis)))
        }

        Stream.bracket(open)(_ => board.change(_.closed(waiting.id))) >>
          games.evalTap {
            case _: Game.AwaitingOpponent => Monad[F].unit
            case _                        => board.change(_.taken(waiting.id))
          }
      }

      private def seated(id: GameId, token: Token): F[Either[GameFault, (Game, Player)]] =
        repository.read(id).map { found =>
          found.toRight(GameFault.NoSuchGame).flatMap(seat(_, token))
        }

      private def announce(changed: Either[GameFault, Game]): F[Unit] =
        changed.fold(_ => Monad[F].unit, events.publish)
    }
  }

  private def seat(game: Game, token: Token): Either[GameFault, (Game, Player)] =
    game.holder(token).toRight(GameFault.NotAPlayer).map(game -> _)
}
