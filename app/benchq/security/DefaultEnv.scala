package benchq.security

import com.mohiva.play.silhouette.api.Env
import com.mohiva.play.silhouette.impl.authenticators.CookieAuthenticator

class DefaultEnv extends Env {
  type I = User
  type A = CookieAuthenticator
}
