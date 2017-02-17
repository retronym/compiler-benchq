package benchq.model

import com.mohiva.play.silhouette.api.Identity

case class User(id: String, login: String, name: String) extends Identity
