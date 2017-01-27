package benchq
package queue

import akka.actor._
import benchq.git.GitRepo
import benchq.influxdb.ResultsDb
import benchq.jenkins.ScalaJenkins
import benchq.model.{Branch, KnownRevisionService}
import benchq.queue.Status._
import benchq.repo.ScalaBuildsRepo
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{Failure, Success, Try}

class TaskQueue(compilerBenchmarkTaskService: CompilerBenchmarkTaskService,
                benchmarkResultService: BenchmarkResultService,
                scalaBuildsRepo: ScalaBuildsRepo,
                scalaJenkins: ScalaJenkins,
                resultsDb: ResultsDb,
                knownRevisionService: KnownRevisionService,
                benchmarkService: BenchmarkService,
                gitRepo: GitRepo,
                system: ActorSystem) {

  val queueActor = system.actorOf(QueueActor.props, "queue-actor")
  val checkNewCommitsActor = system.actorOf(CheckNewCommitsActor.props, "check-new-commits-actor")

  object QueueActor {
    case object PingQueue
    case class ScalaVersionAvailable(taskId: Long, artifactName: Try[Option[String]])
    case class ScalaBuildStarted(taskId: Long, res: Try[Unit])
    case class ScalaBuildFinished(taskId: Long, buildSucceeded: Try[Boolean])
    case class BenchmarkStarted(taskId: Long, res: Try[Unit])
    case class BenchmarkFinished(taskId: Long, results: Try[List[BenchmarkResult]])
    case class ResultsSent(taskId: Long, res: Try[Unit])

    val props = Props(new QueueActor)
  }

  class QueueActor extends Actor {
    import QueueActor._
    def updateStatus(task: CompilerBenchmarkTask, newStatus: Status): Unit =
      compilerBenchmarkTaskService.update(task.id.get, task.copy(status = newStatus)(None))

    def ifSuccess[T](id: Long, res: Try[T])(f: (CompilerBenchmarkTask, T) => Unit): Unit = {
      for (task <- compilerBenchmarkTaskService.findById(id)) res match {
        case Failure(e) =>
          updateStatus(task, RequestFailed(task.status, e.getMessage))
        case Success(t) =>
          f(task, t)
      }
    }

    def receive: Receive = {
      case PingQueue => // traverse entire queue, start jobs for actionable items
        val queue = compilerBenchmarkTaskService.byPriority(StatusCompanion.actionableCompanions)
        var canStartBenchmark = !queue.exists(_.status == WaitForBenchmark)

        for (task <- queue; id = task.id.get) task.status match {
          case CheckScalaVersionAvailable =>
            updateStatus(task, WaitForScalaVersionAvailable)
            scalaBuildsRepo
              .checkBuildAvailable(task.scalaVersion)
              .onComplete(res => self ! ScalaVersionAvailable(id, res))

          case StartScalaBuild =>
            updateStatus(task, WaitForScalaBuild)
            scalaJenkins
              .startScalaBuild(task.scalaVersion)
              .onComplete(res => self ! ScalaBuildStarted(id, res))

          case StartBenchmark if canStartBenchmark =>
            updateStatus(task, WaitForBenchmark)
            scalaJenkins
              .startBenchmark(task)
              .onComplete(res => self ! BenchmarkStarted(id, res))
            canStartBenchmark = false

          case SendResults =>
            updateStatus(task, WaitForSendResults)
            resultsDb
              .sendResults(task, benchmarkResultService.resultsForTask(id))
              .onComplete(res => self ! ResultsSent(id, res))

          case _ =>
        }

      case ScalaVersionAvailable(id, tryArtifactName) =>
        ifSuccess(id, tryArtifactName) { (task, artifactName) =>
          val newStatus = artifactName match {
            case Some(artifact) => StartBenchmark // TODO: pass on artifact name
            case None => StartScalaBuild
          }
          updateStatus(task, newStatus)
          self ! PingQueue
        }

      case ScalaBuildStarted(id, tryRes) =>
        // If the build cannot be started, mark the task as RequestFailed. Otherwise wait for
        // the `ScalaBuildFinished` message triggered by the Jenkins webhook.
        ifSuccess(id, tryRes)((_, _) => ())

      case ScalaBuildFinished(id, tryBuildSucceeded) =>
        ifSuccess(id, tryBuildSucceeded) { (task, succeeded) =>
          if (succeeded) {
            updateStatus(task, StartBenchmark)
            self ! PingQueue
          } else {
            updateStatus(task, RequestFailed(task.status, "Scala build failed."))
          }
        }

      case BenchmarkStarted(id, tryRes) =>
        // If the benchmark cannot be started, mark the task as RequestFailed. Otherwise wait for
        // the `BenchmarkFinished` message triggered by the Benchmark runner.
        ifSuccess(id, tryRes)((_, _) => ())

      case BenchmarkFinished(id, tryResults) =>
        ifSuccess(id, tryResults) { (task, results) =>
          benchmarkResultService.insertResults(results)
          updateStatus(task, SendResults)
          self ! PingQueue
        }

      case ResultsSent(id, tryResult) =>
        ifSuccess(id, tryResult) { (task, _) =>
          updateStatus(task, Done)
        }
    }
  }

  object CheckNewCommitsActor {
    case class Check(branch: Branch)

    val props = Props(new CheckNewCommitsActor)
  }

  class CheckNewCommitsActor extends Actor {
    import CheckNewCommitsActor._
    def receive: Receive = {
      case Check(branch) =>
        knownRevisionService.lastKnownRevision(branch) match {
          case Some(knownRevision) =>
            val newCommits = gitRepo.newMergeCommitsSince(knownRevision)
            newCommits foreach { newCommit =>
              val task =
                CompilerBenchmarkTask(
                  100,
                  Status.CheckScalaVersionAvailable,
                  ScalaVersion(newCommit, Nil)(None),
                  benchmarkService.defaultBenchmarks(knownRevision.branch))(None)
              compilerBenchmarkTaskService.insert(task)
            }
            if (newCommits.nonEmpty)
              queueActor ! QueueActor.PingQueue

          case None =>
            Logger.error(s"Could not find last known revision for branch ${branch.entryName}")
        }
    }
  }
}
