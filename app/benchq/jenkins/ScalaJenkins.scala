package benchq
package jenkins

import benchq.model._
import benchq.repo.ScalaBuildsRepo
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.{WSAuthScheme, WSClient}
import play.api.mvc.Results

import scala.concurrent.Future

class ScalaJenkins(ws: WSClient, config: Config, scalaBuildsRepo: ScalaBuildsRepo) {
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

  object benchmarkJobParams {
    val scalaVersion = "scalaVersion"
    val sbtCommands = "sbtCommands"
    val q = "\""

    def apply(task: CompilerBenchmarkTask, artifact: String): List[(String, String)] = List(
      scalaVersion -> artifact,
      sbtCommands -> task.benchmarks
        .map(b => s""""compilation/jmh:run ${b.name} -p ${b.arguments.mkString(" ")}"""")
        .mkString("[", ", ", "]")
    )
  }

  def startBenchmark(task: CompilerBenchmarkTask): Future[Unit] = {
    scalaBuildsRepo.checkBuildAvailable(task.scalaVersion) flatMap {
      case Some(artifact) =>
        ws.url(host + "job/compiler-benchmark/buildWithParameters")
          .withAuth(user, token, WSAuthScheme.BASIC)
          .withQueryString(benchmarkJobParams(task, artifact): _*)
          .post(Results.EmptyContent())
          .map(_ => ())
      case None =>
        throw new Exception(s"No Scala build found for ${task.scalaVersion}")
    }
  }
}
