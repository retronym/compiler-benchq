package benchq

import benchq.git.GitRepo
import benchq.queue.TaskQueue
import com.softwaremill.macwire._
import controllers.Assets
import play.api.ApplicationLoader.Context
import play.api._
import play.api.routing.Router
import router.Routes

class AppApplicationLoader extends ApplicationLoader {
  def load(context: Context): Application = {
    // https://www.playframework.com/documentation/2.5.x/ScalaCompileTimeDependencyInjection
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }

    new MyComponents(context).application
  }
}

class MyComponents(context: Context) extends BuiltInComponentsFromContext(context) {
  lazy val assets: Assets = wire[Assets]
  lazy val router: Router = {
    lazy val prefix = "/"
    wire[Routes]
  }

  lazy val homeController: HomeController = wire[HomeController]

  lazy val queue: TaskQueue = wire[TaskQueue]
  lazy val db: DB = wire[DB]
  lazy val gitRepo: GitRepo = wire[GitRepo]
  lazy val webhooks: Webhooks = wire[Webhooks]
}
