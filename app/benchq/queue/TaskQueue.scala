package benchq
package queue

import akka.actor._
import benchq.bench.BenchmarkRunner
import benchq.influxdb.ResultsDb
import benchq.jenkins.ScalaJenkins
import benchq.queue.Status._
import benchq.repo.ScalaBuildsRepo
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{Failure, Success, Try}

case object PingQueue
case class ScalaVersionAvailable(taskId: Long, versionAvailable: Try[Boolean])
case class ScalaBuildStarted(taskId: Long, res: Try[Unit])
case class ScalaBuildFinished(taskId: Long, buildSucceeded: Try[Boolean])
case class BenchmarkStarted(taskId: Long, res: Try[Unit])
case class BenchmarkFinished(taskId: Long, results: Try[List[BenchmarkResult]])
case class ResultsSent(taskId: Long, res: Try[Unit])

class TaskQueue(compilerBenchmarkTaskService: CompilerBenchmarkTaskService,
                benchmarkResultService: BenchmarkResultService,
                benchmarkRunner: BenchmarkRunner,
                scalaBuildsRepo: ScalaBuildsRepo,
                scalaJenkins: ScalaJenkins,
                resultsDb: ResultsDb) {

  class QueueActor extends Actor {
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
            scalaBuildsRepo
              .checkBuildAvailable(task.scalaVersion)
              .onComplete(res => self ! ScalaVersionAvailable(id, res))
            updateStatus(task, WaitForScalaVersionAvailable)

          case StartScalaBuild =>
            scalaJenkins
              .startScalaBuild(task.scalaVersion)
              .onComplete(res => self ! ScalaBuildStarted(id, res))
            updateStatus(task, WaitForScalaBuild)

          case StartBenchmark if canStartBenchmark =>
            benchmarkRunner
              .startBenchmark(task)
              .onComplete(res => self ! BenchmarkStarted(id, res))
            updateStatus(task, WaitForBenchmark)
            canStartBenchmark = false

          case SendResults =>
            resultsDb
              .sendResults(task, benchmarkResultService.resultsForTask(id))
              .onComplete(res => self ! ResultsSent(id, res))
            updateStatus(task, WaitForSendResults)

          case _ =>
        }

      case ScalaVersionAvailable(id, tryVersionAvailable) =>
        ifSuccess(id, tryVersionAvailable) { (task, available) =>
          updateStatus(task, if (available) StartBenchmark else StartScalaBuild)
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
        }

      case ResultsSent(id, tryResult) =>
        ifSuccess(id, tryResult) { (task, _) =>
          updateStatus(task, Done)
        }
    }
  }
}
