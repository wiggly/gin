package wiggly.gin.server.config

import cats.syntax.show.*
import com.comcast.ip4s.{ipv4, port}
import org.scalacheck.Gen
import pureconfig.error.ConfigReaderFailures
import weaver.scalacheck.Checkers
import weaver.{Expectations, SimpleIOSuite}

import scala.concurrent.duration.DurationInt

object AppConfigSuite extends SimpleIOSuite with Checkers {

  /** Loads `env` and hands the configuration to `check`, failing the test if it would not load. */
  private def loaded(env: (String, String)*)(check: AppConfig => Expectations): Expectations = {
    AppConfig.from(env.toMap) match {
      case Right(config)  => check(config)
      case Left(failures) =>
        failure(show"expected a configuration, got: ${failures.prettyPrint()}")
    }
  }

  /** The mirror image: fails the test if `env` would load, rather than being rejected. */
  private def rejected(
      env: (String, String)*
  )(check: ConfigReaderFailures => Expectations): Expectations = {
    AppConfig.from(env.toMap) match {
      case Left(failures) => check(failures)
      case Right(config)  => failure(show"expected the load to fail, got: ${config.toString}")
    }
  }

  pureTest("defaults are usable inside a container with no environment set") {
    loaded() { config =>
      expect.eql(config.http.host, ipv4"0.0.0.0") and
        expect.eql(config.http.port, port"8080") and
        expect.eql(config.http.shutdownTimeout, 30.seconds)
    }
  }

  pureTest("every setting is overridable from the environment") {
    loaded(
      "GIN_HTTP_HOST"             -> "127.0.0.1",
      "GIN_HTTP_PORT"             -> "9000",
      "GIN_HTTP_SHUTDOWN_TIMEOUT" -> "5 seconds"
    ) { config =>
      expect.eql(config.http.host, ipv4"127.0.0.1") and
        expect.eql(config.http.port, port"9000") and
        expect.eql(config.http.shutdownTimeout, 5.seconds)
    }
  }

  test("any port the operating system could bind survives the round trip") {
    forall(Gen.choose(1, 65535)) { chosen =>
      loaded("GIN_HTTP_PORT" -> chosen.toString) { config =>
        expect.eql(config.http.port.value, chosen)
      }
    }
  }

  test("a value outside the port range fails the load rather than wrapping around") {
    val outOfRange = Gen.oneOf(
      Gen.choose(Int.MinValue, -1),
      Gen.choose(65536, Int.MaxValue)
    )

    forall(outOfRange) { chosen =>
      rejected("GIN_HTTP_PORT" -> chosen.toString) { failures =>
        expect(failures.prettyPrint().contains("http.port"))
      }
    }
  }

  pureTest("an unparseable value fails the load rather than falling back to the default") {
    rejected("GIN_HTTP_PORT" -> "not-a-port") { failures =>
      expect(failures.prettyPrint().contains("http.port"))
    }
  }
}
