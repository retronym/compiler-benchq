package benchq
package queue

import akka.actor._
import benchq.bench.BenchmarkRunner
import benchq.queue.Status._
import benchq.repo.ScalaBuildsRepo
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{Failure, Try}

object PingQueue
case class ScalaVersionAvailable(taskId: Long, res: Try[Boolean])
case class ScalaBuildStarted(taskId: Long, res: Try[Unit])
case class ScalaBuildFinished(taskId: Long, buildSucceeded: Boolean)
case class BenchmarkStarted(taskId: Long, res: Try[Unit])
case class BenchmarkFinished(taskId: Long, results: List[BenchmarkResult])

class TaskQueue(compilerBenchmarkTaskService: CompilerBenchmarkTaskService,
                benchmarkRunner: BenchmarkRunner,
                scalaBuildsRepo: ScalaBuildsRepo) {

  class QueueActor extends Actor { self =>
    def receive = {
      case PingQueue =>
        val queue = compilerBenchmarkTaskService.byPriority()
        var canStartBenchmark = !queue.exists(_.status == WaitForBenchmark)

        for (task <- queue; id = task.id.get) task.status match {
          case StartBenchmark if canStartBenchmark =>
            benchmarkRunner.startBenchmark(task).onComplete(res => self ! BenchmarkStarted(id, res))
            compilerBenchmarkTaskService.update(id, task.copy(status = WaitForBenchmark)(None))
            canStartBenchmark = false
        }

      case ScalaBuildStarted(id, res) => res match {
        case Failure(e) =>
          for (task <- compilerBenchmarkTaskService.findById(id))
            compilerBenchmarkTaskService.update(id, task.copy(status = RequestFailed(task.status, e.getMessage))(None))
        case _ =>
      }
    }
  }
}
