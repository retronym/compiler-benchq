package benchq.security

import com.mohiva.play.silhouette.api.{Identity, LoginInfo}
import com.mohiva.play.silhouette.api.services.IdentityService
import com.mohiva.play.silhouette.impl.providers.CommonSocialProfile

import scala.concurrent.Future

case class User(username: String) extends Identity

class UserService extends IdentityService[User] {
  def retrieve(loginInfo: LoginInfo): Future[Option[User]] =
    Future.successful(Some(User(loginInfo.providerID)))

  def save(profile: CommonSocialProfile): Future[User] =
    Future.successful(User(profile.loginInfo.providerID))
}
