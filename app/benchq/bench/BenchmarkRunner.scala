package benchq
package bench

import benchq.queue.CompilerBenchmarkTask

import scala.concurrent.Future

class BenchmarkRunner {
  def startBenchmark(compilerBenchmarkTask: CompilerBenchmarkTask): Future[Unit] = {
    Future.successful(())
  }
}
