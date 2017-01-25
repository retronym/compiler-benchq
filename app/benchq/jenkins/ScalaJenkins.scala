package benchq
package jenkins

import benchq.queue.ScalaVersion
import play.api.libs.ws.{WSAuthScheme, WSClient}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.mvc.Results

import scala.concurrent.Future

class ScalaJenkins(ws: WSClient, config: Config) {
  import config.ScalaJenkins._

  object buildJobParams {
    val repoUser = "repo_user"
    val repoName = "repo_name"
    val repoRef = "repo_ref"
    val sbtBuildTask = "sbtBuildTask"
    val testStability = "testStability"

    // the build name (`displayName`) is defined in the job configuration in jenkins
    def apply(sha: String): List[(String, String)] = List(
      repoUser -> "scala",
      repoName -> "scala",
      repoRef -> sha,
      sbtBuildTask -> "version", // a no-opt task to prevent the default `testAll`
      testStability -> "no"
    )
  }

  def buildJobUrl(scalaVersion: ScalaVersion) = {
    // TODO: correct job according to git sha
    host + "job/scala-2.12.x-integrate-bootstrap/"
  }

  def startScalaBuild(scalaVersion: ScalaVersion): Future[Unit] = {
    ws.url(buildJobUrl(scalaVersion) + "buildWithParameters")
      .withAuth(user, token, WSAuthScheme.BASIC)
      .withQueryString(buildJobParams(scalaVersion.sha): _*)
      .post(Results.EmptyContent())
      .map(_ => ())
  }
}
