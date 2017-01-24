package benchq
package influxdb

import benchq.queue.{BenchmarkResult, CompilerBenchmarkTask}
import okhttp3.Interceptor.Chain
import okhttp3._
import org.influxdb.dto.Query
import org.influxdb.dto.QueryResult.Series
import org.influxdb.{InfluxDB, InfluxDBFactory}
import play.api.Configuration

import scala.collection.convert.decorateAsScala._
import scala.concurrent.Future

class ResultsDb(config: Configuration) {
  private def configString(path: String): String =
    config.getString(path).getOrElse(throw config.globalError(s"Missing config: $path"))

  private def trimSl(s: String) = s.replaceFirst("^/*", "").replaceFirst("/*$", "")

  private val influxBaseUrl = trimSl(configString("influx.baseUrl"))
  private val influxUrlPath = "/" + trimSl(configString("influx.urlPath"))
  private val influxUrl = influxBaseUrl + influxUrlPath + "/"

  private val influxUser = configString("influx.user")
  private val influxPassword = configString("influx.password")
  private val influxDbName = "scala_benchmark"

  def sendResults(task: CompilerBenchmarkTask, results: List[BenchmarkResult]): Future[Unit] = {
    Future.successful(())
  }

  // utilities for console interaction: `sbt console`, `scala> resultsDb.createDb()`

  def query(s: String): List[Series] = withConnection { conn =>
    val r = conn.query(new Query(s, influxDbName))
    r.getResults.asScala.flatMap(_.getSeries.asScala).toList
  }

  def withConnection[T](body: InfluxDB => T): T = {
    val conn = connect()
    try body(conn)
    finally conn.close()
  }

  def connect(): InfluxDB = {
    val client = new OkHttpClient.Builder()

    // work around https://github.com/influxdata/influxdb-java/issues/268
    if (!influxUrlPath.isEmpty)
      client.addNetworkInterceptor(new Interceptor {
        override def intercept(chain: Chain): Response = {
          val fixedUrl = chain
            .request()
            .url()
            .newBuilder()
            .encodedPath(influxUrlPath + chain.request().url().encodedPath())
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
