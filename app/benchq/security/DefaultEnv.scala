package benchq.security

import com.mohiva.play.silhouette.api.Env
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator

class DefaultEnv extends Env {
  type I = User
  type A = JWTAuthenticator
}
