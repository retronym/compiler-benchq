package controllers

import benchq.security.{DefaultEnv, UserService}
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.api.{LoginEvent, Silhouette}
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.impl.providers.{CommonSocialProfileBuilder, SocialProvider, SocialProviderRegistry}
import play.api.Logger
import play.api.i18n.{I18nSupport, Messages, MessagesApi}
import play.api.mvc.{Action, Controller}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

// TODO: revR

class SocialAuthController(val messagesApi: MessagesApi,
                           silhouette: Silhouette[DefaultEnv],
                           userService: UserService,
                           authInfoRepository: AuthInfoRepository,
                           socialProviderRegistry: SocialProviderRegistry)
    extends Controller with I18nSupport {

  def authenticate(provider: String) = Action.async { implicit request =>
    (socialProviderRegistry.get[SocialProvider](provider) match {
      case Some(p: SocialProvider with CommonSocialProfileBuilder) =>
        p.authenticate().flatMap {
          case Left(result) => Future.successful(result)
          case Right(authInfo) =>
            for {
              profile <- p.retrieveProfile(authInfo)
              user <- userService.save(profile)
              authInfo <- authInfoRepository.save(profile.loginInfo, authInfo)
              authenticator <- silhouette.env.authenticatorService.create(profile.loginInfo)
              value <- silhouette.env.authenticatorService.init(authenticator)
              result <- silhouette.env.authenticatorService
                .embed(value, Redirect(routes.HomeController.tasks()).flashing("success" -> "from social auth"))
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
        Redirect(routes.HomeController.tasks())
          .flashing("failure" -> Messages("could.not.authenticate"))
    }
  }
}
