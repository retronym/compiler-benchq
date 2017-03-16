package benchq.security

import benchq.Config
import benchq.model.User
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.services.IdentityService

import scala.collection.mutable
import scala.concurrent.Future

class UserService(config: Config) extends IdentityService[User] {
  val users: mutable.HashMap[String, User] = mutable.HashMap.empty

  def retrieve(loginInfo: LoginInfo): Future[Option[User]] = {
    Future.successful(users.get(loginInfo.providerKey))
  }

  def save(profile: GithubProfile): Future[User] = {
    if (config.silhouette.allowedUsers(profile.login)) {
      val r = User(profile.id, profile.login, profile.name)
      users += profile.loginInfo.providerKey -> r
      Future.successful(r)
    } else
      Future.failed(UnauthorizedUserException(profile.login))
  }
}

case class UnauthorizedUserException(login: String)
    extends Exception(s"User $login is not authorized")
