package benchq
package influxdb

import benchq.model._
import okhttp3.Interceptor.Chain
import okhttp3._
import org.influxdb.dto.Query
import org.influxdb.dto.QueryResult.Series
import org.influxdb.{InfluxDB, InfluxDBFactory}

import scala.collection.convert.decorateAsScala._
import scala.concurrent.Future

class ResultsDb(config: Config) {
  import config.influxDb._

  def sendResults(task: CompilerBenchmarkTask, results: List[BenchmarkResult]): Future[Unit] = {
    Future.successful(())
  }

  // utility for console interaction: `sbt console`, `scala> resultsDb.query(...)`

  def query(s: String): List[Series] = withConnection { conn =>
    val r = conn.query(new Query(s, dbName))
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
    if (!urlPath.isEmpty)
      client.addNetworkInterceptor(new Interceptor {
        override def intercept(chain: Chain): Response = {
          val fixedUrl = chain
            .request()
            .url()
            .newBuilder()
            .encodedPath(urlPath + chain.request().url().encodedPath())
          chain.proceed(chain.request().newBuilder().url(fixedUrl.build()).build())
        }
      })

    client.authenticator(new Authenticator {
      override def authenticate(route: Route, response: Response): Request = {
        val credential = Credentials.basic(user, password)
        response
          .request()
          .newBuilder()
          .header("Authorization", credential)
          .build()
      }
    })

    InfluxDBFactory.connect(url, user, password, client)
  }
}
