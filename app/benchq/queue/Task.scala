package benchq
package queue

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import benchq.queue.Status._
import play.api.db.Database

// TODO: split this file up, one per class / service. also move to the package `benchq.model`.

case class ScalaVersion(sha: String, compilerOptions: List[String])(val id: Option[Long])

class ScalaVersionService(database: Database) {

  /**
   * Get the `id` of a [[ScalaVersion]], insert it if it doesn't exist yet.
   */
  def getIdOrInsert(scalaVersion: ScalaVersion): Long = database.withConnection { implicit conn =>
    def optionId(option: String): Long = {
      SQL"select id from compilerOption where opt = $option".as(scalar[Long].singleOpt) getOrElse {
        SQL"insert into compilerOption (opt) values ($option)".executeInsert(scalar[Long].single)
      }
    }

    def insert(): Long = {
      val id = SQL"insert into scalaVersion (sha) values (${scalaVersion.sha})"
        .executeInsert(scalar[Long].single)
      for ((option, idx) <- scalaVersion.compilerOptions.iterator.zipWithIndex)
        SQL"insert into scalaVersionCompilerOption values ($id, ${optionId(option)}, $idx)"
          .executeInsert()
      id
    }

    scalaVersion.id.getOrElse {
      findBySha(scalaVersion.sha)
        .find(_ == scalaVersion)
        .flatMap(_.id)
        .getOrElse(insert())
    }
  }

  private def optsForId(id: Long)(implicit conn: Connection): List[String] = {
    SQL"""select * from scalaVersionCompilerOption as x
          left join compilerOption as o on x.compilerOptionId = o.id
          where x.scalaVersionId = $id
          order by x.idx"""
      .as(SqlParser.str("opt").*)
  }

  def findById(id: Long): Option[ScalaVersion] = database.withConnection { implicit conn =>
    SQL"select sha from scalaVersion where id = $id"
      .as(scalar[String].singleOpt)
      .map(ScalaVersion(_, optsForId(id))(Some(id)))
  }

  def findBySha(sha: String): List[ScalaVersion] = database.withConnection { implicit conn =>
    SQL"select id from scalaVersion where sha = $sha"
      .as(scalar[Long].*)
      .map(id => ScalaVersion(sha, optsForId(id))(Some(id)))
  }

  def delete(id: Long): Unit = database.withConnection { implicit conn =>
    // also deletes from scalaVersionCompilerOption, `cascade` foreign key constraint
    SQL"delete from scalaVersion where id = $id".executeUpdate()
    // clean unreferenced compiler options
    SQL"""delete from compilerOption where id not in
          (select distinct compilerOptionId from scalaVersionCompilerOption)"""
      .executeUpdate()
  }
}

case class Benchmark(name: String, arguments: List[String])(val id: Option[Long])

class BenchmarkService(database: Database) {

  /**
   * Get the `id` of a [[Benchmark]], insert it if it doesn't exist yet.
   */
  def getIdOrInsert(benchmark: Benchmark): Long = database.withConnection { implicit conn =>
    def insert(): Long = {
      val id = SQL"insert into benchmark (name) values (${benchmark.name})"
        .executeInsert(scalar[Long].single)
      for ((arg, idx) <- benchmark.arguments.iterator.zipWithIndex)
        SQL"insert into benchmarkArgument values ($id, $arg, $idx)"
          .executeInsert()
      id
    }

    benchmark.id.getOrElse {
      findByName(benchmark.name)
        .find(_ == benchmark)
        .flatMap(_.id)
        .getOrElse(insert())
    }
  }

  private def argsForId(id: Long)(implicit conn: Connection): List[String] = {
    SQL"select arg, idx from benchmarkArgument where benchmarkId = $id order by idx asc"
      .as(SqlParser.str("arg").*)
  }

  def findById(id: Long): Option[Benchmark] = database.withConnection { implicit conn =>
    SQL"select name from benchmark where id = $id"
      .as(scalar[String].singleOpt)
      .map(Benchmark(_, argsForId(id))(Some(id)))
  }

  def findByName(name: String): List[Benchmark] = database.withConnection { implicit conn =>
    SQL"select id from benchmark where name = $name"
      .as(scalar[Long].*)
      .map(id => Benchmark(name, argsForId(id))(Some(id)))
  }

  def delete(id: Long): Unit = database.withConnection { implicit conn =>
    // also deletes from benchmarkArgument, `cascade` foreign key constraint
    SQL"delete from benchmark where id = $id".executeUpdate()
  }
}

sealed trait Status {
  def companion: StatusCompanion
  def name: String
}

sealed trait StatusCompanion {
  def companion: StatusCompanion = this
  def name: String = StatusCompanion.companionName(this)
}

object Status {
  case object CheckScalaVersionAvailable extends Status with StatusCompanion
  case object WaitForScalaVersionAvailable extends Status with StatusCompanion
  case object StartScalaBuild extends Status with StatusCompanion
  case object WaitForScalaBuild extends Status with StatusCompanion
  case object StartBenchmark extends Status with StatusCompanion
  case object WaitForBenchmark extends Status with StatusCompanion
  case class RequestFailed(previousStatus: Status, message: String) extends Status {
    def companion: StatusCompanion = RequestFailed
    def name: String = companion.name
  }
  object RequestFailed extends StatusCompanion
}

object StatusCompanion {
  private val companionName: Map[StatusCompanion, String] = {
    def name(s: StatusCompanion) = s.getClass.getName.split('$').last
    List(CheckScalaVersionAvailable,
         WaitForScalaVersionAvailable,
         StartScalaBuild,
         WaitForScalaBuild,
         StartBenchmark,
         WaitForBenchmark,
         RequestFailed)
      .map(s => (s, name(s)))
      .toMap
  }

  private val nameToCompanion: Map[String, StatusCompanion] = companionName.map(_.swap)

  def allCompanions: Set[StatusCompanion] = companionName.keySet

  def companion(s: String): StatusCompanion = nameToCompanion(s)
}

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

  private val taskParser = {
    import SqlParser._
    long("id") ~ int("priority") ~ str("status") ~ long("scalaVersionId")
  }

  private val requestFailedFieldsParser = {
    SqlParser.str("previousStatus") ~ SqlParser.str("message")
  }

  private def getTasks(query: SimpleSql[Row]): List[CompilerBenchmarkTask] = {
    database.withConnection { implicit conn =>
      def benchmarks(taskId: Long): List[Benchmark] = {
        SQL"""select benchmarkId, idx from compilerBenchmarkTaskBenchmark
              where compilerBenchmarkTaskId = $taskId order by idx asc"""
          .as(SqlParser.long("benchmarkId").*)
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

  def byPriority(status: Set[StatusCompanion] = StatusCompanion.allCompanions)
    : List[CompilerBenchmarkTask] = {
    // Set[String] is spliced as a list -- using `mkString` wouldn't work.
    // See also http://stackoverflow.com/questions/9528273/in-clause-in-anorm
    val as = status.map(_.name)
    getTasks(SQL"#$selectFromTask where status in ($as) order by priority asc")
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

// TODO: this is just a first stab. data: name-value pairs for benchmark results, metadata
class BenchmarkResult(benchmark: Benchmark, data: Map[String, String])
