package controllers

import javax.inject._

import benchq.Config
import benchq.model.Status._
import benchq.model._
import play.api.data.Forms._
import play.api.data._
import play.api.data.validation.Constraints
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.mvc._
import views._

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController(config: Config,
                     compilerBenchmarkTaskService: CompilerBenchmarkTaskService,
                     knownRevisionService: KnownRevisionService,
                     val messagesApi: MessagesApi)
    extends Controller
    with I18nSupport {
  import config.Http._

  def untrail(path: String) = Action {
    MovedPermanently(externalUrlPrefix + "/" + path)
  }

  val RQueue = Redirect(revR(routes.HomeController.queue()))

  def index = Action(RQueue)

  def queue(showDone: Boolean = false) = Action { implicit request =>
    val inProgress = compilerBenchmarkTaskService.byPriority(StatusCompanion.allCompanions - Done)
    val done = if (showDone) Some(compilerBenchmarkTaskService.byPriority(Set(Done))) else None
    Ok(html.queue(inProgress, done))
  }

  val RBranches = Redirect(revR(routes.HomeController.branches()))

  def branches = Action { implicit request =>
    val branches = Branch.values.toList.sortBy(_.entryName)
    Ok(html.branches(branches.map(b =>
      (b, knownRevisionService.lastKnownRevision(b).map(_.revision)))))
  }

  // patterns are pushed to the client (html5 form validation), thanks play-bootstrap!
  val revisionForm = Form(
    Forms.single("revision" -> nonEmptyText.verifying(
      Constraints.pattern("[0-9a-f]{40}".r, error = "Not a valid sha"))))

  def editKnownRevision(branch: String) = Action {
    Branch.withNameOption(branch) match {
      case Some(b) =>
        val knownRevision = knownRevisionService.lastKnownRevision(b).map(_.revision).getOrElse("")
        Ok(html.editBranch(b.entryName, revisionForm.fill(knownRevision)))
      case None =>
        RBranches.flashing("failure" -> s"Cannot edit known revision, unknown branch: $branch")
    }
  }

  def updateKnownRevision(branch: String) = Action { implicit request =>
    revisionForm.bindFromRequest.fold(
      formWithErrors => BadRequest(html.editBranch(branch, formWithErrors)),
      revision => {
        Branch.withNameOption(branch) match {
          case Some(b) =>
            knownRevisionService.updateOrInsert(KnownRevision(b, revision))
            RBranches.flashing("success" -> s"Updated known revision for $branch")

          case None =>
            RBranches.flashing(
              "failure" -> s"Could not update last revision, unknown branch: $branch")
        }
      }
    )
  }
}
