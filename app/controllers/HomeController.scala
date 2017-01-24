package controllers

import javax.inject._

import benchq.queue.Status.Done
import benchq.queue.{CompilerBenchmarkTaskService, StatusCompanion}
import play.api.mvc._
import views._

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController(compilerBenchmarkTaskService: CompilerBenchmarkTaskService)
    extends Controller {

  val Home = Redirect(routes.HomeController.queue())

  def index = Action(Home)

  def queue() = Action { implicit request =>
    Ok(html.queue(compilerBenchmarkTaskService.byPriority(StatusCompanion.allCompanions - Done)))
  }
}
