package benchq.security

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.HTTPLayer
import com.mohiva.play.silhouette.impl.exceptions.ProfileRetrievalException
import com.mohiva.play.silhouette.impl.providers._
import com.mohiva.play.silhouette.impl.providers.oauth2.{BaseGitHubProvider, GitHubProvider}
import play.api.http.HeaderNames
import play.api.libs.json.JsValue

import scala.concurrent.Future

class CustomGithubProvider(protected val httpLayer: HTTPLayer,
                           protected val stateProvider: OAuth2StateProvider,
                           val settings: OAuth2Settings)
    extends BaseGitHubProvider {
  override type Self = CustomGithubProvider
  type Profile = GithubProfile
  override val profileParser = new SocialProfileParser[JsValue, GithubProfile, OAuth2Info] {
    def parse(content: JsValue, authInfo: OAuth2Info): Future[GithubProfile] = Future.successful {
      val id = (content \ "id").as[Long].toString
      val login = (content \ "login").as[String]
      val name = (content \ "name").asOpt[String].getOrElse(login)
      GithubProfile(LoginInfo(GitHubProvider.ID, id), id, login, name)
    }
  }
  // overriden to backport https://github.com/mohiva/play-silhouette/pull/576/files
  override protected def buildProfile(authInfo: OAuth2Info): Future[Profile] = {
    httpLayer.url(urls("api").stripSuffix("?access_token=%s")).withHeaders(HeaderNames.AUTHORIZATION -> s"Bearer ${authInfo.accessToken}").get().flatMap { response =>
      val json = response.json
      (json \ "message").asOpt[String] match {
        case Some(msg) =>
          val docURL = (json \ "documentation_url").asOpt[String]

          throw new ProfileRetrievalException(GitHubProvider.SpecifiedProfileError.format(id, msg, docURL))
        case _ => profileParser.parse(json, authInfo)
      }
    }
  }

  override def withSettings(f: (Settings) => Settings) =
    new CustomGithubProvider(httpLayer, stateProvider, f(settings))
}

case class GithubProfile(loginInfo: LoginInfo, id: String, login: String, name: String)
    extends SocialProfile
