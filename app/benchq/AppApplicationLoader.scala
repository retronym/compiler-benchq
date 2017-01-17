package benchq

import benchq.git.GitRepo
import benchq.influxdb.ResultsDb
import benchq.queue.TaskQueue
import com.softwaremill.macwire._
import controllers.Assets
import play.api.ApplicationLoader.Context
import play.api._
import play.api.db.{DBComponents, Database, HikariCPComponents}
import play.api.routing.Router
import router.Routes

class AppApplicationLoader extends ApplicationLoader {
  def load(context: Context): Application = {
    // https://www.playframework.com/documentation/2.5.x/ScalaCompileTimeDependencyInjection
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }

    new BenchQComponents(context).application
  }
}

class BenchQComponents(context: Context)
    extends BuiltInComponentsFromContext(context)
    with DBComponents
    with HikariCPComponents {
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

  lazy val homeController: HomeController = wire[HomeController]
}
