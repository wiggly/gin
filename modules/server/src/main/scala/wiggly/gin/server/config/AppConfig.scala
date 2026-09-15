package wiggly.gin.server.config

import cats.syntax.parallel.*
import ciris.{ConfigDecoder, ConfigKey, ConfigValue, Effect}
import com.comcast.ip4s.{Host, Hostname, IpAddress, Port}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

/** Configuration for the HTTP listener.
  *
  * @param shutdownTimeout
  *   how long in-flight requests are given to finish once a shutdown has been requested.
  */
final case class HttpConfig(host: Host, port: Port, shutdownTimeout: FiniteDuration)

final case class AppConfig(http: HttpConfig)

object AppConfig {

  /** The configuration as read from the process environment, per 12-factor.
    *
    * The environment is passed in rather than read directly so that the description of the
    * configuration stays a pure value that tests can feed arbitrary environments to.
    */
  def from(env: String => Option[String]): ConfigValue[Effect, AppConfig] =
    httpConfig(env).map(AppConfig.apply)

  /** The configuration of the running process. */
  val fromEnv: ConfigValue[Effect, AppConfig] = from(sys.env.get)

  private def httpConfig(env: String => Option[String]): ConfigValue[Effect, HttpConfig] = {
    val value = variable(env)

    (
      // 0.0.0.0 rather than loopback: a container's port is only reachable if we
      // listen on the external interface.
      value("GIN_HTTP_HOST").default("0.0.0.0").as[Host],
      value("GIN_HTTP_PORT").default("8080").as[Port],
      value("GIN_HTTP_SHUTDOWN_TIMEOUT").default("30 seconds").as[FiniteDuration]
    ).parMapN(HttpConfig.apply)
  }

  private def variable(env: String => Option[String])(name: String): ConfigValue[Effect, String] = {
    val key = ConfigKey.env(name)

    ConfigValue.suspend {
      env(name).fold(ConfigValue.missing[String](key))(ConfigValue.loaded(key, _))
    }
  }

  // An IP address is tried before a hostname: "0.0.0.0" is a valid hostname as far as the
  // grammar is concerned, and binding to it as a name is not what anyone means by it.
  private given ConfigDecoder[String, Host] =
    ConfigDecoder[String].mapOption("Host") { value =>
      IpAddress.fromString(value).orElse(Hostname.fromString(value))
    }

  private given ConfigDecoder[String, Port] =
    ConfigDecoder[String].mapOption("Port")(Port.fromString)

  private given ConfigDecoder[String, FiniteDuration] =
    ConfigDecoder[String].mapOption("FiniteDuration") { value =>
      Try(Duration(value)).toOption.collect { case finite: FiniteDuration => finite }
    }
}
