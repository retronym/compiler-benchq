package benchq
package jenkins

import benchq.queue.ScalaVersion

import scala.concurrent.Future

class ScalaJenkins {
  def startScalaBuild(scalaVerison: ScalaVersion): Future[Unit] = {
    Future.successful(())
  }
}
