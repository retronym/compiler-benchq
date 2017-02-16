package benchq.security

import benchq.Config
import com.mohiva.play.silhouette.api.actions.UnsecuredErrorHandler
import play.api.mvc.Results.Redirect
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.Future

class CustomUnsecuredErrorHandler(appConfig: Config) extends UnsecuredErrorHandler {
  import appConfig.Http.revR
  def onNotAuthorized(implicit request: RequestHeader): Future[Result] = {
    Future.successful(Redirect(revR(controllers.routes.HomeController.tasks())))
  }
}
