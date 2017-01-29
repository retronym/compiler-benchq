package controllers

import javax.inject._

import benchq.model.Status._
import benchq.model._
import play.api.mvc._
import views._

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController(compilerBenchmarkTaskService: CompilerBenchmarkTaskService)
    extends Controller {

  def untrail(path: String) = Action {
    MovedPermanently(routes.HomeController.index().url + "/" + path)
  }

  val Home = Redirect(routes.HomeController.queue())

  def index = Action(Home)

  def queue() = Action { implicit request =>
    Ok(html.queue(compilerBenchmarkTaskService.byPriority(StatusCompanion.allCompanions - Done)))
  }
}
