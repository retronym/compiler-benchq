package benchq

import benchq.git.GitRepo
import benchq.influxdb.ResultsDb
import benchq.queue._
import com.softwaremill.macwire._
import controllers.Assets
import controllers.HomeController
import play.api.ApplicationLoader.Context
import play.api._
import play.api.db.evolutions.EvolutionsComponents
import play.api.db.{DBComponents, Database, HikariCPComponents}
import play.api.routing.Router
import router.Routes

class AppApplicationLoader extends ApplicationLoader {
  def load(context: Context): Application = {
    // https://www.playframework.com/documentation/2.5.x/ScalaCompileTimeDependencyInjection
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }

    val components = new BenchQComponents(context)
    components.applicationEvolutions // force the lazy val to ensure evolutions are applied
    components.application
  }
}

class BenchQComponents(context: Context)
    extends BuiltInComponentsFromContext(context)
    with DBComponents
    with HikariCPComponents
    with EvolutionsComponents {
  lazy val assets: Assets = wire[Assets]
  lazy val router: Router = {
    lazy val prefix = "/"
    wire[Routes]
  }
  lazy val database: Database = dbApi.database("default")

  lazy val toolDb: ToolDb = wire[ToolDb]
  lazy val resultsDb: ResultsDb = wire[ResultsDb]

  lazy val queue: TaskQueue = wire[TaskQueue]
  lazy val gitRepo: GitRepo = wire[GitRepo]
  lazy val webhooks: Webhooks = wire[Webhooks]

  lazy val scalaVersionService: ScalaVersionService = wire[ScalaVersionService]
  lazy val benchmarkService: BenchmarkService = wire[BenchmarkService]
  lazy val compilerBenchmarkTaskService: CompilerBenchmarkTaskService =
    wire[CompilerBenchmarkTaskService]

  lazy val homeController: HomeController = wire[HomeController]
}
