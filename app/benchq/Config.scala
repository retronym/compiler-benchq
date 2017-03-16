package benchq

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import play.api.Configuration
import play.api.mvc.Call

abstract class RevRouteFix {
  def apply(c: Call): String
  def call(c: Call): Call
}

// URL settings end in '/'
class Config(config: Configuration) {
  private def trimSl(s: String) = s.replaceFirst("^/*", "").replaceFirst("/*$", "")

  object Http {
    private def nonEmpty(c: String) = c != "" && c != "/"

    implicit object revR extends RevRouteFix {
      def apply(c: Call): String = reverseRoutePrefix + c.toString
      def call(c: Call): Call = c.copy(url = reverseRoutePrefix + c.url)
    }

    /**
     * A prefix that should be added to reverse routes to get a valid path. If a prefix is set by
     * play using `play.http.context`, this field is empty: play already adds the prefix to reverse
     * routes.
     *
     * When deploying the app behind a reverse proxy which exposes it under a sub-path,
     * `play.http.context` has to remain empty, so play doesn't include the prefix in reverse
     * routes.
     *
     * This field can be added to reverse routes to get a valid path in any situation.
     */
    val reverseRoutePrefix =
      config.getString("app.proxyContext").filter(nonEmpty).map(c => "/" + trimSl(c)).getOrElse("")

    /**
     * The prefix under which this app is running. The prefix can either come from play's
     * `play.http.context` setting, or from a setting in the reverse proxy.
     *
     * This field can be used when building paths "by hand".
     */
    val externalUrlPrefix =
      config
        .getString("play.http.context")
        .filter(nonEmpty)
        .map(c => "/" + trimSl(c)) getOrElse reverseRoutePrefix
  }

  val scalaScalaRepo = "scala/scala"

  case class AppConfig(defaultJobPriority: Int)
  val appConfig = config.underlying.as[AppConfig]("app")

  case class InfluxDb(baseUrl: String, urlPath: String, user: String, password: String) {
    val url = baseUrl + urlPath + "/"
    val dbName = "scala_benchmark"
  }
  val influxDb = {
    val c = config.underlying.as[InfluxDb]("influx")
    c.copy(baseUrl = trimSl(c.baseUrl), urlPath = "/" + trimSl(c.urlPath))
  }

  case class ScalaJenkins(host: String, user: String, token: String)
  val scalaJenkins = {
    val c = config.underlying.as[ScalaJenkins]("scalaJenkins")
    c.copy(host = trimSl(c.host) + "/")
  }

  case class ScalaBuildsRepo(baseUrl: String,
                             integrationRepo: String,
                             tempRepo: String,
                             user: String,
                             password: String) {
    val integrationRepoUrl = baseUrl + integrationRepo + "/"
    val tempRepoUrl = baseUrl + tempRepo + "/"
  }
  val scalaBuildsRepo = {
    val c = config.underlying.as[ScalaBuildsRepo]("scalaBuildsRepo")
    c.copy(baseUrl = trimSl(c.baseUrl) + "/")
  }

  case class GitRepo(checkoutLocation: String)
  val gitRepo = config.underlying.as[GitRepo]("gitRepo")

  case class Silhouette(cookieSignerKey: String, allowedUsers: Set[String])
  val silhouette = config.underlying.as[Silhouette]("silhouette")
}
