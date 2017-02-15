package benchq.security

import com.mohiva.play.silhouette.api.actions.SecuredErrorHandler
import com.mohiva.play.silhouette.impl.providers.oauth2.GitHubProvider
import play.api.mvc.Results.Redirect
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.Future

class CustomSecuredErrorHandler extends SecuredErrorHandler {
  def onNotAuthenticated(implicit request: RequestHeader): Future[Result] = {
    Future.successful(Redirect(controllers.routes.SocialAuthController.authenticate(GitHubProvider.ID)))
  }

  def onNotAuthorized(implicit request: RequestHeader): Future[Result] = {
    Future.successful(Redirect(controllers.routes.HomeController.tasks()).flashing("success" -> "secured not auth"))
  }
}
