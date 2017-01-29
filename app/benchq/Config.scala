package benchq

import play.api.Configuration

class Config(config: Configuration) {
  private def configString(path: String): String =
    config.getString(path).getOrElse(throw config.globalError(s"Missing config: $path"))

  private def trimSl(s: String) = s.replaceFirst("^/*", "").replaceFirst("/*$", "")

  object Http {
    private def nonEmpty(c: String) = c != "" && c != "/"

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

  object InfluxDb {
    private val influxBaseUrl = trimSl(configString("influx.baseUrl"))
    val influxUrlPath = "/" + trimSl(configString("influx.urlPath"))
    val influxUrl = influxBaseUrl + influxUrlPath + "/"

    val influxUser = configString("influx.user")
    val influxPassword = configString("influx.password")
    val influxDbName = "scala_benchmark"
  }

  object ScalaJenkins {
    val host = trimSl(configString("scalaJenkins.host")) + "/"
    val user = configString("scalaJenkins.user")
    val token = configString("scalaJenkins.token")
  }

  object ScalaBuildsRepo {
    val host = trimSl(configString("scalaBuildsRepo.host"))
    val repo = configString("scalaBuildsRepo.repo")
    val user = configString("scalaBuildsRepo.user")
    val password = configString("scalaBuildsRepo.password")
  }

  object GitRepo {
    val checkoutLocation = configString("gitRepo.checkoutLocation")
  }
}
