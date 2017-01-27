package benchq

import benchq.model.Branch
import benchq.queue.TaskQueue
import play.api.Logger
import play.api.libs.json.JsValue
import play.api.mvc._

class Webhooks(taskQueue: TaskQueue) extends Controller {
  def github: Action[JsValue] = Action(parse.json) { implicit req =>
    req.headers
      .get("X-GitHub-Event")
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
      .getOrElse(BadRequest("Missing `X-GitHub-Event` header"))
  }
}
