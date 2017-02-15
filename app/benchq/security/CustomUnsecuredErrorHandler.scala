package benchq.security

import com.mohiva.play.silhouette.api.actions.UnsecuredErrorHandler
import play.api.mvc.Results.Redirect
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.Future

class CustomUnsecuredErrorHandler extends UnsecuredErrorHandler {
  def onNotAuthorized(implicit request: RequestHeader): Future[Result] = {
    Future.successful(Redirect(controllers.routes.HomeController.tasks()).flashing("success" -> "unsecured not auth"))
  }
}
