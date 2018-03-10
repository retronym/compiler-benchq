package benchq

import benchq.model.Branch
import benchq.queue.TaskQueue
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.mvc._

import scala.util.{Failure, Success, Try}

class Webhooks(taskQueue: TaskQueue, config: Config) extends Controller {
  import config.scalaJenkins

  val xGhEventHeader = "X-GitHub-Event"

  def github: Action[JsValue] = Action(parse.json) { implicit req =>
    Logger.debug(s"Github webhook request: ${req.headers} - ${req.body}")
    req.headers
      .get(xGhEventHeader)
      .map({
        case "push" =>
          // https://developer.github.com/v3/activity/events/types/#pushevent
          val branchName = (req.body \ "ref").as[String].split('/').last
          Branch.withNameOption(branchName) match {
            case Some(branch) =>
              Logger.info(s"Github webhook: push to $branch")
              taskQueue.checkNewCommitsActor ! taskQueue.CheckNewCommitsActor.Check(branch)
            case _ =>
              Logger.info(s"Github webhook: push to unknown branch $branchName")
          }
          Ok
        case e =>
          Logger.info(s"Github webhook: ignoring event $e")
          NotImplemented(s"Server does not handle webhook event $e")
      })
      .getOrElse({
        Logger.info(s"Github webhook: no $xGhEventHeader header")
        BadRequest(s"Missing `$xGhEventHeader` header")
      })
  }

  val SuccessStr = "SUCCESS"
  val FinalizedStr = "FINALIZED"

  def jenkins: Action[JsValue] = Action(parse.json) { implicit req =>
    Logger.debug(s"Jenkins webhook request: ${req.body}")

    val name = (req.body \ "name").as[String]
    val phase = (req.body \ "build" \ "phase").as[String]
    val taskIdOpt =
      (req.body \ "build" \ "parameters" \ "benchqTaskId")
        .asOpt[String]
        .flatMap(s => Try(s.toInt).toOption)

    def status = (req.body \ "build" \ "status").as[String]
    def url = (req.body \ "build" \ "full_url").as[String]

    (name, phase, taskIdOpt) match {
      case (scalaJenkins.benchmarkJobName, FinalizedStr, Some(taskId)) =>
        val result =
          if (status == SuccessStr) Success(Nil) // storing results is not yet implemented
          else
            Failure(new Exception(s"Benchmark job failed: $url"))
        Logger.info(s"Jenkins webhook: $name finalized: $taskId - $result")
        taskQueue.queueActor ! taskQueue.QueueActor.BenchmarkFinished(taskId, result)
        Ok

      case (scalaJenkins.bootstrapJobName, FinalizedStr, Some(taskId)) =>
        val res =
          if (status == SuccessStr) Success(())
          else Failure(new Exception(s"Scala build failed: $url"))
        Logger.info(s"Jenkins webhook: $name finalized: $taskId - $res ")
        taskQueue.queueActor ! taskQueue.QueueActor.ScalaBuildFinished(taskId, res)
        Ok

      case _ =>
        Logger.info(s"Jenkins webhook: unknown event: $name - $phase - $taskIdOpt")
        NotImplemented(s"Server does not handle notification: $name - $phase - $taskIdOpt")
    }
  }

  def travis: Action[JsValue] = Action(parse.json) { implicit req =>
    Logger.debug(s"Travis webhook request: ${req.body}")

    req.headers.get("Travis-Repo-Slug") match {
      case Some(config.scalaScalaRepo) =>
        val buildUrl = (req.body \ "build_url").as[String]
        val commit = (req.body \ "commit").as[String]
        (req.body \ "status").asOpt[Int] match {
          case Some(status) =>
            val res =
              if (status == 0) Success(())
              else Failure(new Exception(s"Travis build failed: $buildUrl"))
            taskQueue.queueActor ! taskQueue.QueueActor.TravisBuildFinished(commit, res)
            Ok

          case _ =>
            Logger.info(s"Travis webhook: notification withouth 'status': $buildUrl")
            NotImplemented
        }

      case repo =>
        Logger.info(s"Travis webhook: not the scala repo: $repo")
        NotImplemented
    }
  }
}
