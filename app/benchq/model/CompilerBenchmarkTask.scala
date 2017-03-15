package benchq
package model

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import benchq.model.Status._
import play.api.db.Database

// Longer-term, the tool could support tasks other than scalac benchmarks, like the
// existing benchmarks in scala/scala/test/benchmarks. For now we just have a single table for
// CompilerBenchmarkTask. If we add more types, split it up in a base table for Task and
// a table for each type.
trait Task {
  def priority: Int
  def status: Status
}

case class CompilerBenchmarkTask(priority: Int,
                                 status: Status,
                                 scalaVersion: ScalaVersion,
                                 benchmarks: List[Benchmark])(val id: Option[Long])
    extends Task

object CompilerBenchmarkTask {
  val defaultPriority = 100
}

class CompilerBenchmarkTaskService(database: Database,
                                   scalaVersionService: ScalaVersionService,
                                   benchmarkService: BenchmarkService) {
  def insert(task: CompilerBenchmarkTask): Long = database.withConnection { implicit conn =>
    val taskId = SQL"""
        insert into compilerBenchmarkTask (priority, status, scalaVersionId)
        values (
          ${task.priority},
          ${task.status.name},
          ${scalaVersionService.getIdOrInsert(task.scalaVersion)})"""
      .executeInsert(scalar[Long].single)
    insertStatusFields(taskId, task.status)
    insertBenchmarks(taskId, task.benchmarks)
    taskId
  }

  private def insertStatusFields(taskId: Long, status: Status)(implicit conn: Connection): Unit = {
    status match {
      case RequestFailed(prev, msg) =>
        SQL"insert into requestFailedFields values ($taskId, ${prev.name}, $msg)"
          .executeInsert()
      case _ =>
    }
  }

  private def insertBenchmarks(taskId: Long, benchmarks: List[Benchmark])(
      implicit conn: Connection): Unit = {
    for ((bench, idx) <- benchmarks.iterator.zipWithIndex) {
      SQL"""insert into compilerBenchmarkTaskBenchmark values (
              $taskId,
              ${benchmarkService.getIdOrInsert(bench)},
              $idx)""".executeInsert()
    }
  }

  private val taskParser = long("id") ~ int("priority") ~ str("status") ~ long("scalaVersionId")

  private val requestFailedFieldsParser = str("previousStatus") ~ str("message")

  private def getTasks(query: SimpleSql[Row]): List[CompilerBenchmarkTask] = {
    database.withConnection { implicit conn =>
      def benchmarks(taskId: Long): List[Benchmark] = {
        SQL"""select benchmarkId, idx from compilerBenchmarkTaskBenchmark
              where compilerBenchmarkTaskId = $taskId order by idx asc"""
          .as(long("benchmarkId").*)
          .map(benchmarkService.findById(_).get)
      }

      def status(id: Long, name: String): Status = StatusCompanion.companion(name) match {
        case s: Status => s
        case RequestFailed =>
          SQL"""select previousStatus, message from requestFailedFields
                where compilerBenchmarkTaskId = $id"""
            .as(requestFailedFieldsParser.single) match {
            case s ~ m => RequestFailed(status(-1, s), m)
          }
      }

      query.as(taskParser.*) map {
        case id ~ priority ~ statusName ~ scalaVersionId =>
          CompilerBenchmarkTask(priority,
                                status(id, statusName),
                                scalaVersionService.findById(scalaVersionId).get,
                                benchmarks(id))(Some(id))
      }
    }
  }

  private def selectFromTask = "select * from compilerBenchmarkTask"

  def findById(id: Long): Option[CompilerBenchmarkTask] = {
    getTasks(SQL"#$selectFromTask where id = $id").headOption
  }

  def byPriority(status: Set[StatusCompanion]): List[CompilerBenchmarkTask] = {
    // Set[String] is spliced as a list -- using `mkString` wouldn't work.
    // See also http://stackoverflow.com/questions/9528273/in-clause-in-anorm
    val as = status.map(_.name)
    getTasks(SQL"#$selectFromTask where status in ($as) order by priority asc")
  }

  def byIndex(status: Set[StatusCompanion]): List[CompilerBenchmarkTask] = {
    val as = status.map(_.name)
    getTasks(SQL"#$selectFromTask where status in ($as) order by id desc")
  }

  def update(id: Long, task: CompilerBenchmarkTask): Unit = database.withConnection {
    implicit conn =>
      val newScalaVersionId = scalaVersionService.getIdOrInsert(task.scalaVersion.copy()(None))
      SQL"""update compilerBenchmarkTask set
            priority = ${task.priority},
            status = ${task.status.name},
            scalaVersionId = $newScalaVersionId
          where id = $id""".executeUpdate()

      SQL"delete from compilerBenchmarkTaskBenchmark where compilerBenchmarkTaskId = $id"
        .executeUpdate()
      SQL"delete from requestFailedFields where compilerBenchmarkTaskId = $id".executeUpdate()
      insertStatusFields(id, task.status)
      insertBenchmarks(id, task.benchmarks)
  }

  def delete(id: Long): Unit = database.withConnection { implicit conn =>
    // also deletes from compilerBenchmarkTask through `cascade` foreign key constraint
    SQL"delete from compilerBenchmarkTask where id = $id".executeUpdate()
  }
}
