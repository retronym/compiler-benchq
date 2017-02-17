package benchq.security

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.HTTPLayer
import com.mohiva.play.silhouette.impl.providers._
import com.mohiva.play.silhouette.impl.providers.oauth2.{BaseGitHubProvider, GitHubProvider}
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
      GithubProfile(LoginInfo(GitHubProvider.ID, id),
                    id,
                    (content \ "login").as[String],
                    (content \ "name").as[String])
    }
  }

  override def withSettings(f: (Settings) => Settings) =
    new CustomGithubProvider(httpLayer, stateProvider, f(settings))
}

case class GithubProfile(loginInfo: LoginInfo, id: String, login: String, name: String)
    extends SocialProfile
