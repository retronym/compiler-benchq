package benchq

import benchq.model.Branch
import benchq.queue.TaskQueue
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.mvc._

import scala.util.{Failure, Success, Try}

class Webhooks(taskQueue: TaskQueue) extends Controller {
  val xGhEventHeader = "X-GitHub-Event"

  def github: Action[JsValue] = Action(parse.json) { implicit req =>
    req.headers
      .get(xGhEventHeader)
      .map({
        case "push" =>
          // https://developer.github.com/v3/activity/events/types/#pushevent
          val branchName = (req.body \ "ref").as[String].split('/').last
          Branch.withNameOption(branchName) match {
            case Some(branch) =>
              taskQueue.checkNewCommitsActor ! taskQueue.CheckNewCommitsActor.Check(branch)
            case _ =>
              Logger.debug(s"Push event to unknown branch $branchName")
          }
          Ok
        case e =>
          NotImplemented(s"Server does not handle webhook event $e")
      })
      .getOrElse(BadRequest(s"Missing `$xGhEventHeader` header"))
  }

  val BootstrapTask = """scala-2\.1\d\.x-integrate-bootstrap""".r
  val SuccessStr = "SUCCESS"
  val FinalizedStr = "FINALIZED"

  def jenkins: Action[JsValue] = Action(parse.json) { implicit req =>
    val name = (req.body \ "name").as[String]
    val phase = (req.body \ "build" \ "phase").as[String]
    val taskIdOpt =
      (req.body \ "build" \ "parameters" \ "benchqTaskId")
        .asOpt[String]
        .flatMap(s => Try(s.toInt).toOption)

    def status = (req.body \ "build" \ "status").as[String]
    def url = (req.body \ "build" \ "full_url").as[String]

    (name, phase, taskIdOpt) match {
      case ("compiler-benchmark", FinalizedStr, Some(taskId)) =>
        val result =
          if (status == SuccessStr) Success(Nil) // storing results is not yet implemented
          else
            Failure(new Exception(s"Benchmark job failed: $url"))
        taskQueue.queueActor ! taskQueue.QueueActor.BenchmarkFinished(taskId, result)

      case (BootstrapTask(), FinalizedStr, Some(taskId)) =>
        val res =
          if (status == SuccessStr) Success(())
          else Failure(new Exception(s"Scala build failed: $url"))
        taskQueue.queueActor ! taskQueue.QueueActor.ScalaBuildFinished(taskId, res)

      case _ =>
    }
    Ok
  }
}
