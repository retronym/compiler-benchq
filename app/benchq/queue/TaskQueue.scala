package benchq
package queue

import akka.actor._
import benchq.git.GitRepo
import benchq.influxdb.ResultsDb
import benchq.jenkins.ScalaJenkins
import benchq.model.Status._
import benchq.model._
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
    case class ScalaBuildFinished(taskId: Long, buildSucceeded: Try[Unit])
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
      compilerBenchmarkTaskService.findById(id) match {
        case Some(task) => res match {
          case Failure(e) =>
            Logger.error(s"Action on task $id failed", e)
            updateStatus(task, RequestFailed(task.status, e.getMessage))
          case Success(t) =>
            f(task, t)
        }

        case None =>
          Logger.error(s"Could not find task for $id")
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
              .startScalaBuild(task)
              .onComplete(res => self ! ScalaBuildStarted(id, res))

          case StartBenchmark if canStartBenchmark =>
            updateStatus(task, WaitForBenchmark)
            canStartBenchmark = false
            scalaJenkins
              .startBenchmark(task)
              .onComplete(res => self ! BenchmarkStarted(id, res))

          case SendResults =>
            updateStatus(task, WaitForSendResults)
            resultsDb
              .sendResults(task, benchmarkResultService.resultsForTask(id))
              .onComplete(res => self ! ResultsSent(id, res))

          case _ =>
        }

      case ScalaVersionAvailable(id, tryArtifactName) =>
        ifSuccess(id, tryArtifactName) { (task, artifactName) =>
          Logger.info(s"Search result for Scala build of ${task.scalaVersion}: $artifactName")
          val newStatus = artifactName match {
            case Some(artifact) =>
              // could pass on artifact name - currently we do another lookup in ScalaJenkins.startBenchmark
              StartBenchmark
            case None =>
              // Check if the Scala version is being built by some other task
              val versionBeingBuilt = compilerBenchmarkTaskService
                .byPriority(Set(StartScalaBuild, WaitForScalaBuild))
                .exists(t => t.id != task.id && t.scalaVersion == task.scalaVersion)
              if (versionBeingBuilt) WaitForScalaBuild
              else StartScalaBuild
          }
          updateStatus(task, newStatus)
          self ! PingQueue
        }

      case ScalaBuildStarted(id, tryRes) =>
        // If the build cannot be started, mark the task as RequestFailed. Otherwise wait for
        // the `ScalaBuildFinished` message triggered by the Jenkins webhook.
        ifSuccess(id, tryRes)((task, _) => {
          Logger.info(s"Started Scala build for task ${task.id}")
        })

      case ScalaBuildFinished(id, tryRes) =>
        ifSuccess(id, tryRes) { (task, _) =>
          Logger.info(s"Scala build finished for task ${task.id}")
          updateStatus(task, StartBenchmark)
          // Update other tasks that are waiting for this Scala version to be built
          compilerBenchmarkTaskService
            .byPriority(Set(WaitForScalaBuild))
            .filter(_.scalaVersion == task.scalaVersion)
            .foreach(task => updateStatus(task, StartBenchmark))
          self ! PingQueue
        }

      case BenchmarkStarted(id, tryRes) =>
        // If the benchmark cannot be started, mark the task as RequestFailed. Otherwise wait for
        // the `BenchmarkFinished` message triggered by the Benchmark runner.
        ifSuccess(id, tryRes)((task, _) => {
          Logger.info(s"Started benchmark job for task ${task.id}")
        })

      case BenchmarkFinished(id, tryResults) =>
        ifSuccess(id, tryResults) { (task, results) =>
          Logger.info(s"Benchmark job finished for task ${task.id}")
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
            Logger.info(s"Starting benchmarks for new commits in $branch: $newCommits")
            newCommits foreach { newCommit =>
              val task =
                CompilerBenchmarkTask(
                  100,
                  model.Status.CheckScalaVersionAvailable,
                  ScalaVersion(newCommit, Nil)(None),
                  benchmarkService.defaultBenchmarks(knownRevision.branch))(None)
              compilerBenchmarkTaskService.insert(task)
            }
            if (newCommits.nonEmpty) {
              knownRevisionService.updateOrInsert(knownRevision.copy(revision = newCommits.head))
              queueActor ! QueueActor.PingQueue
            }

          case None =>
            Logger.error(s"Could not find last known revision for branch ${branch.entryName}")
        }
    }
  }
}
