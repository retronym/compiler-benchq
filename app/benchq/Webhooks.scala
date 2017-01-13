package benchq

import benchq.git.GitRepo
import play.api.libs.json.JsValue
import play.api.mvc._

class Webhooks(gitRepo: GitRepo) extends Controller {
  def github: Action[JsValue] = Action(parse.json) { implicit req =>
    req.headers.get("X-GitHub-Event").map({
      case "push" =>
        gitRepo.checkNewCommits()
        Ok
      case e =>
        NotImplemented(s"server does not handle webhook event $e")
    }).getOrElse(BadRequest("missing `X-GitHub-Event` header"))
  }
}
