package controllers

import javax.inject._

import benchq.Config
import benchq.model.Status._
import benchq.model._
import play.api.mvc._
import views._

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController(config: Config,
                     compilerBenchmarkTaskService: CompilerBenchmarkTaskService,
                     knownRevisionService: KnownRevisionService)
    extends Controller {
  import config.Http._

  def untrail(path: String) = Action {
    MovedPermanently(externalUrlPrefix + "/" + path)
  }

  val Home = Redirect(revR(routes.HomeController.queue()))

  def index = Action(Home)

  def queue = Action { implicit request =>
    Ok(html.queue(compilerBenchmarkTaskService.byPriority(StatusCompanion.allCompanions - Done)))
  }

  def branches = Action { implicit request =>
    val branches = Branch.values.toList.sortBy(_.entryName)
    Ok(html.branches(branches.map(b =>
      (b, knownRevisionService.lastKnownRevision(b).map(_.revision)))))
  }
}
