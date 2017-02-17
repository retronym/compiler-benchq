package benchq.security

import benchq.model.User
import com.mohiva.play.silhouette.api.services.IdentityService
import com.mohiva.play.silhouette.api.{Identity, LoginInfo}

import scala.collection.mutable
import scala.concurrent.Future



class UserService extends IdentityService[User] {
  val users: mutable.HashMap[String, User] = mutable.HashMap.empty

  def retrieve(loginInfo: LoginInfo): Future[Option[User]] = {
    Future.successful(users.get(loginInfo.providerKey))
  }

  def save(profile: GithubProfile): Future[User] = {
    val r = User(profile.id, profile.login, profile.name)
    users += profile.loginInfo.providerKey -> r
    Future.successful(r)
  }
}
