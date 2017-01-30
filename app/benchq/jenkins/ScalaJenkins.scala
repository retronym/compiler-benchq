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
  import config.ScalaJenkins._

  object buildJobParams {
    val repoUser = "repo_user"
    val repoName = "repo_name"
    val repoRef = "repo_ref"
    val sbtBuildTask = "sbtBuildTask"
    val testStability = "testStability"
    val benchqTaskId = "benchqTaskId"

    // the build name (`displayName`) is defined in the job configuration in jenkins
    def apply(task: CompilerBenchmarkTask): List[(String, String)] = List(
      repoUser -> "scala",
      repoName -> "scala",
      repoRef -> task.scalaVersion.sha,
      sbtBuildTask -> "version", // a no-opt task to prevent the default `testAll`
      testStability -> "no",
      benchqTaskId -> task.id.get.toString
    )
  }

  def buildJobUrl(scalaVersion: ScalaVersion): Try[Option[String]] = {
    gitRepo.leastBranchContaining(scalaVersion.sha) map {
      _.map { branch =>
        host + s"job/scala-${branch.entryName}-integrate-bootstrap/"
      }
    }
  }

  def startScalaBuild(task: CompilerBenchmarkTask): Future[Unit] = {
    buildJobUrl(task.scalaVersion) match {
      case Success(Some(url)) =>
        Logger.info(s"Starting Scala build for ${task.id} at $url")
        ws.url(url + "buildWithParameters")
          .withAuth(user, token, WSAuthScheme.BASIC)
          .withQueryString(buildJobParams(task): _*)
          .post(Results.EmptyContent())
          .map(_ => ())

      case Success(None) =>
        val msg = s"Could not start Scala build for ${task.id}, ${task.scalaVersion.sha}, " +
            s"the revision is not in a known branch: ${Branch.values.mkString(", ")}"
        Logger.error(msg)
        Future.failed(new Exception(msg))

      case Failure(e) =>
        val msg = s"Could not start Scala build for ${task.scalaVersion.sha}, " +
            s"querying the git repo failed: ${e.getMessage}"
        Logger.error(msg)
        Future.failed(new Exception(msg))
    }
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
