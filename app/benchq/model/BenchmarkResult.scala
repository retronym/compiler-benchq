package benchq.model

import play.api.db.Database

// TODO: this is just a first stab. data: name-value pairs for benchmark results, metadata
case class BenchmarkResult(taskId: Long, benchmark: Benchmark, data: Map[String, String])

class BenchmarkResultService(database: Database) {
  def resultsForTask(taskId: Long): List[BenchmarkResult] = Nil

  def insertResults(results: List[BenchmarkResult]): Unit = ()
}
