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
                     benchmarkService: BenchmarkService,
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
    val done = if (showDone) Some(compilerBenchmarkTaskService.byIndex(Set(Done))) else None
    Ok(html.tasks(inProgress, done))
  }

  val RBranches = Redirect(revR(routes.HomeController.branches()))

  def branches = Action { implicit request =>
    Ok(html.branches(Branch.sortedValues.map(b =>
      (b, knownRevisionService.lastKnownRevision(b).map(_.revision)))))
  }

  // patterns are pushed to the client (html5 form validation), thanks play-bootstrap!
  val revisionForm: Form[String] = Form(
    single(
      "revision" -> nonEmptyText.verifying(
        Constraints.pattern("[0-9a-f]{40}".r, error = "Not a valid sha"))))

  def editKnownRevision(branch: String) = Action {
    Branch.withNameOption(branch) match {
      case Some(b) =>
        val knownRevision = knownRevisionService.lastKnownRevision(b).map(_.revision).getOrElse("")
        Ok(html.branchEdit(b.entryName, revisionForm.fill(knownRevision)))
      case None =>
        RBranches.flashing("failure" -> s"Cannot edit known revision, unknown branch: $branch")
    }
  }

  def updateKnownRevision(branch: String) = Action { implicit request =>
    revisionForm.bindFromRequest.fold(
      formWithErrors => BadRequest(html.branchEdit(branch, formWithErrors)),
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

  val RBenchmarks = Redirect(revR(routes.HomeController.benchmarks()))

  def benchmarks = Action { implicit request =>
    Ok(html.benchmarks(benchmarkService.all()))
  }

  // newlines in textareas can be \r\n or \n, http://stackoverflow.com/a/14217315/248998
  val benchForm: Form[Benchmark] = Form(
    mapping(
      "name" -> nonEmptyText,
      "arguments" -> text,
      "defaultBranches" -> list(nonEmptyText.verifying(b => Branch.withNameOption(b).nonEmpty))
    )((n, as, bs) =>
      Benchmark(n, as.replace("\r\n", "\n").split("\n").toList, bs.map(Branch.withName))(None))(
      b => Some((b.name, b.arguments.mkString("\n"), b.defaultBranches.map(_.entryName))))
  )

  def allBranchesMapping: List[(String, String)] = {
    val bs = Branch.sortedValues.map(_.entryName)
    bs zip bs
  }

  def editBenchmark(id: Long) = Action { implicit request =>
    benchmarkService.findById(id) match {
      case Some(bm) =>
        Ok(html.benchmarkEdit(id, benchForm.fill(bm), allBranchesMapping))

      case None =>
        RBenchmarks.flashing("failure" -> s"Cannot edit benchmark, unknown id: $id")
    }
  }

  def updateBenchmark(id: Long) = Action { implicit request =>
    benchForm.bindFromRequest.fold(
      formWithErrors => BadRequest(html.benchmarkEdit(id, formWithErrors, allBranchesMapping)),
      benchmark => {
        benchmarkService.update(id, benchmark)
        RBenchmarks.flashing("success" -> s"Updated benchmark $id")
      }
    )
  }

  def newBenchmark = Action { implicit request =>
    Ok(html.benchmarkNew(benchForm, allBranchesMapping))
  }

  def createBenchmark() = Action { implicit request =>
    benchForm.bindFromRequest.fold(
      formWithErrors => BadRequest(html.benchmarkNew(formWithErrors, allBranchesMapping)),
      benchmark => {
        val id = benchmarkService.getIdOrInsert(benchmark)
        RBenchmarks.flashing("success" -> s"Created benchmark $id")
      }
    )
  }

  def deleteBenchmark(id: Long) = Action { implicit request =>
    benchmarkService.delete(id)
    RBenchmarks.flashing("success" -> s"Deleted benchmark $id")
  }
}
