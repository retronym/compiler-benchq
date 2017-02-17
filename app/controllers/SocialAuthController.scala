package controllers

import benchq.Config
import benchq.security.{CustomGithubProvider, DefaultEnv, UserService}
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.api.{LoginEvent, LogoutEvent, Silhouette}
import com.mohiva.play.silhouette.impl.providers.{SocialProvider, SocialProviderRegistry}
import play.api.Logger
import play.api.i18n.{I18nSupport, Messages, MessagesApi}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.{Action, Controller}

import scala.concurrent.Future

class SocialAuthController(val messagesApi: MessagesApi,
                           silhouette: Silhouette[DefaultEnv],
                           userService: UserService,
                           authInfoRepository: AuthInfoRepository,
                           socialProviderRegistry: SocialProviderRegistry,
                           appConfig: Config)
    extends Controller
    with I18nSupport {
  import appConfig.Http.revR
  import silhouette.SecuredAction

  def authenticate(provider: String) = Action.async { implicit request =>
    (socialProviderRegistry.get[SocialProvider](provider) match {
      case Some(p: CustomGithubProvider) =>
        p.authenticate().flatMap {
          case Left(result) => Future.successful(result)
          case Right(authInfo) =>
            for {
              profile <- p.retrieveProfile(authInfo)
              user <- userService.save(profile)
              _ <- authInfoRepository.save(profile.loginInfo, authInfo)
              authenticator <- silhouette.env.authenticatorService.create(profile.loginInfo)
              value <- silhouette.env.authenticatorService.init(authenticator)
              result <- silhouette.env.authenticatorService
                .embed(value,
                       Redirect(revR(routes.HomeController.tasks()))
                         .flashing("success" -> "Login Successful"))
            } yield {
              silhouette.env.eventBus.publish(LoginEvent(user, request))
              result
            }
        }
      case _ =>
        Future.failed(
          new ProviderException(s"Cannot authenticate with unexpected social provider $provider"))
    }).recover {
      case e: ProviderException =>
        Logger.error("Unexpected provider error", e)
        Redirect(revR(routes.HomeController.tasks()))
          .flashing("failure" -> Messages("could.not.authenticate"))
    }
  }

  def signOut = SecuredAction.async { implicit request =>
    val result = Redirect(routes.HomeController.tasks())
    silhouette.env.eventBus.publish(LogoutEvent(request.identity, request))
    silhouette.env.authenticatorService.discard(request.authenticator, result)
  }
}
