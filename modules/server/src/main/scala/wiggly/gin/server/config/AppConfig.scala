package wiggly.gin.server.config

import com.comcast.ip4s.{Host, Port}
import com.typesafe.config.ConfigFactory
import pureconfig.error.{ConfigReaderException, ConfigReaderFailures}
import pureconfig.module.ip4s.given
import pureconfig.{ConfigReader, ConfigSource}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/** Configuration for the HTTP listener.
  *
  * @param shutdownTimeout
  *   how long in-flight requests are given to finish once a shutdown has been requested.
  */
final case class HttpConfig(host: Host, port: Port, shutdownTimeout: FiniteDuration)
    derives ConfigReader

final case class AppConfig(http: HttpConfig) derives ConfigReader

object AppConfig {

  /** The configuration as read from the process environment, per 12-factor.
    *
    * The environment is passed in rather than read directly so that the description of the
    * configuration stays a pure value that tests can feed arbitrary environments to. It is laid
    * over `application.conf`, which holds both the defaults and the substitutions that put a
    * variable where it belongs.
    */
  def from(env: Map[String, String]): Either[ConfigReaderFailures, AppConfig] =
    ConfigSource
      .fromConfig(ConfigFactory.parseMap(env.asJava, "environment"))
      .withFallback(ConfigSource.resources("application.conf"))
      .at("gin")
      .load[AppConfig]

  /** The configuration of the running process, as something a `main` can fail with. */
  def fromEnv: Either[ConfigReaderException[AppConfig], AppConfig] =
    from(sys.env).left.map(ConfigReaderException[AppConfig](_))
}
