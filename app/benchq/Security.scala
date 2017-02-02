package benchq

import java.util

import org.pac4j.core.authorization.authorizer.ProfileAuthorizer
import org.pac4j.core.client.Clients
import org.pac4j.core.config.{Config => OAuthConfig}
import org.pac4j.core.context.WebContext
import org.pac4j.core.profile.CommonProfile
import org.pac4j.oauth.client.GitHubClient
import org.pac4j.play.http.DefaultHttpActionAdapter
import org.pac4j.play.{ApplicationLogoutController, CallbackController}

class Security(config: Config) {
  class BenchqAuthorizer extends ProfileAuthorizer[CommonProfile] {
    def isProfileAuthorized(context: WebContext, profile: CommonProfile): Boolean =
      profile != null && profile.getUsername == "lrytz"

    def isAuthorized(context: WebContext, profiles: util.List[CommonProfile]): Boolean =
      isAnyAuthorized(context, profiles)
  }

  val githubClient = new GitHubClient("d2142e3366656e7277e5", config.OAuth.githubSecret)
  val clients = new Clients(config.OAuth.githubCallbackUrl, githubClient)
  val oauthConfig = new OAuthConfig(clients)
  oauthConfig.addAuthorizer("benchqAuthorizer", new BenchqAuthorizer)
  oauthConfig.setHttpActionAdapter(new DefaultHttpActionAdapter())

  val callbackController = new CallbackController()
//  callbackController.setDefaultUrl("/?defaulturlafterlogout")

  val logoutController = new ApplicationLogoutController()
//  logoutController.setDefaultUrl("/")
}
