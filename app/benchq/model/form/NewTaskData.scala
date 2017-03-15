package benchq.model
package form

case class NewTaskData(priority: Int,
                       repo: String,
                       revisions: List[String],
                       benchmarks: List[Benchmark])
