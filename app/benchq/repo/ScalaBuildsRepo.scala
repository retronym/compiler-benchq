package benchq
package repo

import benchq.queue.ScalaVersion

import scala.concurrent.Future

class ScalaBuildsRepo {
  def checkBuildAvailable(scalaVersion: ScalaVersion): Future[Boolean] = {
    Future.successful(true)
  }
}
