package benchq
package influxdb

import okhttp3.Interceptor.Chain
import okhttp3._
import org.influxdb.{InfluxDB, InfluxDBFactory}
import play.api.Configuration

class ResultsDb(config: Configuration) {
  private def configString(path: String): String =
    config.getString(path).getOrElse(throw config.globalError(s"Missing config: $path"))

  private val influxUrl = configString("influx.user")
  private val influxUser = configString("influx.user")
  private val influxPassword = configString("influx.password")

  def connect(): InfluxDB = {
    val client = new OkHttpClient.Builder()

    client.addNetworkInterceptor(new Interceptor {
      override def intercept(chain: Chain): Response = {
        val fixedUrl = chain
          .request()
          .url()
          .newBuilder()
          .encodedPath(
            "/influx/" + chain.request().url().encodedPath().replaceFirst("/influxdb", ""))
        chain.proceed(chain.request().newBuilder().url(fixedUrl.build()).build())
      }
    })

    client.authenticator(new Authenticator {
      override def authenticate(route: Route, response: Response): Request = {
        val credential = Credentials.basic(influxUser, influxPassword)
        response
          .request()
          .newBuilder()
          .header("Authorization", credential)
          .build()
      }
    })

    InfluxDBFactory.connect(influxUrl, influxUser, influxPassword, client)
  }
}
