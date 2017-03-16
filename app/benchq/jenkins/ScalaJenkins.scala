package benchq
package jenkins

import benchq.git.GitRepo
import benchq.model._
import benchq.repo.ScalaBuildsRepo
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.{WSAuthScheme, WSClient}
import play.api.mvc.Results

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

class ScalaJenkins(ws: WSClient,
                   config: Config,
                   scalaBuildsRepo: ScalaBuildsRepo,
                   gitRepo: GitRepo) {
  import config.scalaJenkins._

  object buildJobParams {
    // the build name (`displayName`) is defined in the job configuration in jenkins
    def apply(task: CompilerBenchmarkTask): List[(String, String)] = {
      val Array(repoU, repoN) = task.scalaVersion.repo.split('/')
      // use scala-integration if the commit is already merged to scala/scala
      val repoUrl =
        if (task.scalaVersion.repo == config.scalaScalaRepo && gitRepo.isMerged(
              task.scalaVersion.sha))
          config.scalaBuildsRepo.integrationRepoUrl
        else config.scalaBuildsRepo.tempRepoUrl

      // format: off
      List(
        "repo_user"          -> repoU,
        "repo_name"          -> repoN,
        "repo_ref"           -> task.scalaVersion.sha,
        "sbtBuildTask"       -> "version", // a no-opt task to prevent the default `testAll`
        "testStability"      -> "no",
        "publishToSonatype"  -> "no",
        "integrationRepoUrl" -> repoUrl,
        "benchqTaskId"       -> task.id.get.toString
      )
      // format: on
    }
  }

  // the 2.11.x / 2.12.x / 2.13.x bootstrap jobs are identical, we can pick any
  val buildJobUrl = host + "job/scala-2.13.x-integrate-bootstrap/"

  def startScalaBuild(task: CompilerBenchmarkTask): Future[Unit] = {
    Logger.info(s"Starting Scala build for ${task.id} at $buildJobUrl")
    ws.url(buildJobUrl + "buildWithParameters")
      .withAuth(user, token, WSAuthScheme.BASIC)
      .withQueryString(buildJobParams(task): _*)
      .post(Results.EmptyContent())
      .map(_ => ())
  }

  object benchmarkJobParams {
    val scalaVersion = "scalaVersion"
    val sbtCommands = "sbtCommands"
    val benchqTaskId = "benchqTaskId"
    val q = "\""

    def apply(task: CompilerBenchmarkTask, artifact: String): List[(String, String)] = List(
      scalaVersion -> artifact,
      sbtCommands -> task.benchmarks
        .map(b => s""""compilation/jmh:run ${b.name} -p ${b.arguments.mkString(" ")}"""")
        .mkString("[", ", ", "]"),
      benchqTaskId -> task.id.get.toString
    )
  }

  def startBenchmark(task: CompilerBenchmarkTask): Future[Unit] = {
    scalaBuildsRepo.checkBuildAvailable(task.scalaVersion) flatMap {
      case Some(artifact) =>
        Logger.info(s"Starting benchmark job for ${task.id} using $artifact")
        ws.url(host + "job/compiler-benchmark/buildWithParameters")
          .withAuth(user, token, WSAuthScheme.BASIC)
          .withQueryString(benchmarkJobParams(task, artifact): _*)
          .post(Results.EmptyContent())
          .map(_ => ())
      case None =>
        val msg = s"Could not start benchmark, no Scala build found for ${task.scalaVersion}"
        Logger.error(msg)
        throw new Exception(msg)
    }
  }
}
