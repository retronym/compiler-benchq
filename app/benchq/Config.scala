package benchq

import play.api.Configuration

class Config(config: Configuration) {
  private def configString(path: String): String =
    config.getString(path).getOrElse(throw config.globalError(s"Missing config: $path"))

  private def trimSl(s: String) = s.replaceFirst("^/*", "").replaceFirst("/*$", "")

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
}
