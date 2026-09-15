package wiggly.gin.server.config

import cats.effect.IO
import cats.syntax.show.*
import ciris.ConfigException
import com.comcast.ip4s.{ipv4, port}
import org.scalacheck.Gen
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

import scala.concurrent.duration.DurationInt

object AppConfigSuite extends SimpleIOSuite with Checkers {

  private def load(env: (String, String)*): IO[AppConfig] =
    AppConfig.from(env.toMap.get).load[IO]

  test("defaults are usable inside a container with no environment set") {
    load().map { config =>
      expect.eql(config.http.host, ipv4"0.0.0.0") and
        expect.eql(config.http.port, port"8080") and
        expect.eql(config.http.shutdownTimeout, 30.seconds)
    }
  }

  test("every setting is overridable from the environment") {
    load(
      "GIN_HTTP_HOST"             -> "127.0.0.1",
      "GIN_HTTP_PORT"             -> "9000",
      "GIN_HTTP_SHUTDOWN_TIMEOUT" -> "5 seconds"
    ).map { config =>
      expect.eql(config.http.host, ipv4"127.0.0.1") and
        expect.eql(config.http.port, port"9000") and
        expect.eql(config.http.shutdownTimeout, 5.seconds)
    }
  }

  test("any port the operating system could bind survives the round trip") {
    forall(Gen.choose(1, 65535)) { chosen =>
      load("GIN_HTTP_PORT" -> chosen.toString).map { config =>
        expect.eql(config.http.port.value, chosen)
      }
    }
  }

  test("a value outside the port range fails the load rather than wrapping around") {
    val outOfRange = Gen.oneOf(
      Gen.choose(Int.MinValue, 0),
      Gen.choose(65536, Int.MaxValue)
    )

    forall(outOfRange) { chosen =>
      load("GIN_HTTP_PORT" -> chosen.toString).attempt.map {
        case Left(error: ConfigException) => expect(error.getMessage.contains("GIN_HTTP_PORT"))
        case other => failure(show"expected a ConfigException, got: ${other.toString}")
      }
    }
  }

  test("an unparseable value fails the load rather than falling back to the default") {
    load("GIN_HTTP_PORT" -> "not-a-port").attempt.map {
      case Left(error: ConfigException) => expect(error.getMessage.contains("GIN_HTTP_PORT"))
      case other => failure(show"expected a ConfigException, got: ${other.toString}")
    }
  }
}
