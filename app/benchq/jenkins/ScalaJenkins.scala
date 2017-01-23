package benchq
package jenkins

import benchq.queue.ScalaVersion

import scala.util.{Success, Try}

class ScalaJenkins {
  def startScalaBuild(scalaVerison: ScalaVersion): Try[Unit] = {
    Success(())
  }
}
