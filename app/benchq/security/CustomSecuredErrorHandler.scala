package benchq.security

import benchq.Config
import com.mohiva.play.silhouette.api.actions.SecuredErrorHandler
import com.mohiva.play.silhouette.impl.providers.oauth2.GitHubProvider
import play.api.mvc.Results.Redirect
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.Future

class CustomSecuredErrorHandler(appConfig: Config) extends SecuredErrorHandler {
  import appConfig.Http.revR

  def onNotAuthenticated(implicit request: RequestHeader): Future[Result] = {
    Future.successful(
      Redirect(revR(controllers.routes.SocialAuthController.authenticate(GitHubProvider.ID))))
  }

  def onNotAuthorized(implicit request: RequestHeader): Future[Result] = {
    Future.successful(Redirect(revR(controllers.routes.HomeController.tasks())))
  }
}
