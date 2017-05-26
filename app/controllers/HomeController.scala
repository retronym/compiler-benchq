package controllers

import javax.inject._

import benchq.Config
import benchq.model.Status._
import benchq.model._
import benchq.queue.TaskQueue
import benchq.security.DefaultEnv
import com.mohiva.play.silhouette.api.Silhouette
import play.api.data.Forms._
import play.api.data._
import play.api.data.validation.Constraints
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.mvc._
import views._

import scala.concurrent.Future

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController(appConfig: Config,
                     compilerBenchmarkTaskService: CompilerBenchmarkTaskService,
                     knownRevisionService: KnownRevisionService,
                     benchmarkService: BenchmarkService,
                     scalaVersionService: ScalaVersionService,
                     taskQueue: TaskQueue,
                     silhouette: Silhouette[DefaultEnv],
                     val messagesApi: MessagesApi)
    extends Controller
    with I18nSupport {
  import appConfig.Http._
  import appConfig.appConfig.defaultJobPriority
  import silhouette.{SecuredAction, UserAwareAction}

  val shaPattern = "[0-9a-f]{40}".r
  val tagPattern = """v\d+\.\d+\.\d+(?:-.+)?""".r

  // patterns are pushed to the client (html5 form validation), thanks play-bootstrap!
  val shaMapping: Mapping[String] =
    nonEmptyText.verifying(Constraints.pattern(shaPattern, error = "Not a valid sha"))

  def splitString(s: String): List[String] = s.replace("\r\n", "\n").split("\n").toList

  def untrail(path: String) = Action {
    MovedPermanently(externalUrlPrefix + "/" + path)
  }

  val RTasks = Redirect(revR(routes.HomeController.tasks()))

  def index = Action(RTasks)

  def tasks(showDone: Boolean = false) = UserAwareAction { implicit request =>
    val inProgress = compilerBenchmarkTaskService.byPriority(StatusCompanion.allCompanions - Done)
    val done = if (showDone) Some(compilerBenchmarkTaskService.byIndex(Set(Done))) else None
    Ok(html.tasks(request.identity)(inProgress, done))
  }

  val taskForm: Form[form.NewTaskData] = Form(
    mapping(
      "priority" -> number(min = 0),
      "repo" -> nonEmptyText,
      "revisions" -> nonEmptyText.verifying(
        "Each line needs to contain a valid 40-character sha or a tag on scala/scala",
        rs => splitString(rs).forall(rev => rev.matches(tagPattern.regex) || rev.matches(shaPattern.regex))),
      "benchmarks" -> list(longNumber).verifying("No benchmark selected", _.nonEmpty)
    )(
      (p, r, rs, bs) =>
        form.NewTaskData(p, r, splitString(rs), bs.flatMap(benchmarkService.findById))
    )(
      taskData =>
        Some(
          (taskData.priority,
           taskData.repo,
           taskData.revisions.mkString("\n"),
           taskData.benchmarks.map(_.id.get))))
  )

  def allBenchmarksById: Seq[(String, String)] =
    benchmarkService.all().map(b => (b.id.get.toString, b.toString))

  def defaultTaskData =
    form.NewTaskData(defaultJobPriority,
                     appConfig.scalaScalaRepo,
                     Nil,
                     benchmarkService.defaultBenchmarks(Branch.v2_12_x))

  def newTask = SecuredAction { implicit request =>
    Ok(
      html.taskNew(request.identity)(taskForm.fill(defaultTaskData),
                                     allBenchmarksById,
                                     defaultJobPriority))
  }

  def createTask() = SecuredAction { implicit request =>
    taskForm.bindFromRequest.fold(
      formWithErrors =>
        BadRequest(
          html.taskNew(request.identity)(formWithErrors, allBenchmarksById, defaultJobPriority)),
      taskData => {
        for (rev <- taskData.revisions) {
          val v = scalaVersionService.fromShaOrTag(taskData.repo, rev)
          val task =
            CompilerBenchmarkTask(taskData.priority, initial, v, taskData.benchmarks)(None)
          compilerBenchmarkTaskService.insert(task)
        }
        taskQueue.queueActor ! taskQueue.QueueActor.PingQueue
        RTasks.flashing("success" -> s"Task added to queue")
      }
    )
  }

  def editTask(id: Long) = SecuredAction { implicit request =>
    compilerBenchmarkTaskService.findById(id) match {
      case Some(t) => Ok(html.taskEdit(request.identity)(t))
      case None => RTasks.flashing("failure" -> s"Task not found: $id")
    }
  }

  def updateTask(id: Long) = SecuredAction { implicit request =>
    def t = compilerBenchmarkTaskService.findById(id).get
    request.body.asFormUrlEncoded.flatMap(_.get("action")).flatMap(_.headOption) match {
      case Some("Use as Template") =>
        Ok(
          html.taskNew(request.identity)(taskForm.fill(
                                           form.NewTaskData(t.priority,
                                                            t.scalaVersion.repo,
                                                            List(t.scalaVersion.sha),
                                                            t.benchmarks)),
                                         allBenchmarksById,
                                         defaultJobPriority))

      case Some("Mark Done") =>
        compilerBenchmarkTaskService.update(id, t.copy(status = Done)(None))
        RTasks.flashing("success" -> s"Task $id marked done")

      case Some("Restart") =>
        compilerBenchmarkTaskService.update(id, t.copy(status = initial)(None))
        taskQueue.queueActor ! taskQueue.QueueActor.PingQueue
        RTasks.flashing("success" -> s"Task $id restarted")

      case Some("Delete") =>
        compilerBenchmarkTaskService.delete(id)
        RTasks.flashing("success" -> s"Task $id deleted")

      case _ =>
        RTasks.flashing("failure" -> s"Unknown action for task $id")
    }
  }

  val RBranches = Redirect(revR(routes.HomeController.branches()))

  def branches = UserAwareAction { implicit request =>
    Ok(html.branches(request.identity)(Branch.sortedValues.map(b =>
      (b, knownRevisionService.lastKnownRevision(b).map(_.revision)))))
  }

  val revisionForm: Form[String] = Form(single("revision" -> shaMapping))

  def editKnownRevision(branch: String) = SecuredAction { implicit request =>
    Branch.withNameOption(branch) match {
      case Some(b) =>
        val knownRevision = knownRevisionService.lastKnownRevision(b).map(_.revision).getOrElse("")
        Ok(html.branchEdit(request.identity)(b.entryName, revisionForm.fill(knownRevision)))
      case None =>
        RBranches.flashing("failure" -> s"Cannot edit known revision, unknown branch: $branch")
    }
  }

  def updateKnownRevision(branch: String) = SecuredAction { implicit request =>
    revisionForm.bindFromRequest.fold(
      formWithErrors => BadRequest(html.branchEdit(request.identity)(branch, formWithErrors)),
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

  def benchmarks = UserAwareAction { implicit request =>
    Ok(html.benchmarks(request.identity)(benchmarkService.all()))
  }

  // newlines in textareas can be \r\n or \n, http://stackoverflow.com/a/14217315/248998
  val benchForm: Form[Benchmark] = Form(
    mapping(
      "command" -> nonEmptyText,
      "defaultBranches" -> list(nonEmptyText.verifying(b => Branch.withNameOption(b).nonEmpty))
    )((c, bs) => Benchmark(c, bs.map(Branch.withName))(None))(b =>
      Some((b.command, b.defaultBranches.map(_.entryName))))
  )

  def allBranchesMapping: List[(String, String)] = {
    val bs = Branch.sortedValues.map(_.entryName)
    bs zip bs
  }

  def editBenchmark(id: Long) = SecuredAction { implicit request =>
    benchmarkService.findById(id) match {
      case Some(bm) =>
        Ok(html.benchmarkEdit(request.identity)(id, benchForm.fill(bm), allBranchesMapping))

      case None =>
        RBenchmarks.flashing("failure" -> s"Cannot edit benchmark, unknown id: $id")
    }
  }

  def updateBenchmark(id: Long) = SecuredAction { implicit request =>
    benchForm.bindFromRequest.fold(
      formWithErrors =>
        BadRequest(html.benchmarkEdit(request.identity)(id, formWithErrors, allBranchesMapping)),
      benchmark => {
        benchmarkService.update(id, benchmark)
        RBenchmarks.flashing("success" -> s"Updated benchmark $id")
      }
    )
  }

  def newBenchmark = SecuredAction { implicit request =>
    Ok(html.benchmarkNew(request.identity)(benchForm, allBranchesMapping))
  }

  def createBenchmark() = SecuredAction { implicit request =>
    benchForm.bindFromRequest.fold(
      formWithErrors =>
        BadRequest(html.benchmarkNew(request.identity)(formWithErrors, allBranchesMapping)),
      benchmark => {
        val id = benchmarkService.getIdOrInsert(benchmark)
        RBenchmarks.flashing("success" -> s"Created benchmark $id")
      }
    )
  }

  def deleteBenchmark(id: Long) = SecuredAction { implicit request =>
    benchmarkService.delete(id)
    RBenchmarks.flashing("success" -> s"Deleted benchmark $id")
  }
}
