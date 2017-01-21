package benchq
package queue

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import enumeratum._
import play.api.db.Database

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

sealed trait Action extends EnumEntry

object Action extends Enum[Action] {
  val values = findValues

  case object CheckScalaVersionAvailable extends Action
  case object StartScalaBuild extends Action
  case object WaitForScalaBuild extends Action
  case object StartBenchmark extends Action
  case object WaitForBenchmark extends Action
}

// Longer-term, the tool could support tasks other than scalac benchmarks, like the
// existing benchmarks in scala/scala/test/benchmarks. For now we just have a single table for
// CompilerBenchmarkTask. If we add more types, split it up in a base table for Task and
// a table for each type.
trait Task {
  def priority: Int
  def nextAction: Action
}

case class CompilerBenchmarkTask(priority: Int,
                                 nextAction: Action,
                                 scalaVersion: ScalaVersion,
                                 benchmarks: List[Benchmark])(val id: Option[Long])
    extends Task

class CompilerBenchmarkTaskService(database: Database,
                                   scalaVersionService: ScalaVersionService,
                                   benchmarkService: BenchmarkService) {
  def insert(task: CompilerBenchmarkTask): Long = database.withConnection { implicit conn =>
    val taskId = SQL"""
        insert into compilerBenchmarkTask (priority, nextAction, scalaVersionId)
        values (
          ${task.priority},
          ${task.nextAction.entryName},
          ${scalaVersionService.getIdOrInsert(task.scalaVersion)})"""
      .executeInsert(scalar[Long].single)
    insertBenchmarks(taskId, task.benchmarks)
    taskId
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

  val taskParser = {
    import SqlParser._
    (long("id") ~ int("priority") ~ str("nextAction") ~ long("scalaVersionId")) map {
      case i ~ p ~ a ~ s => (i, p, Action.withName(a), s)
    }
  }

  // TODO: provide a method to get a task by id, factor out a common query using #$filter,
  // https://www.playframework.com/documentation/2.5.x/ScalaAnorm#SQL-queries-using-String-Interpolation
  def byPriority(nextActions: Set[Action] = Action.values.toSet): List[CompilerBenchmarkTask] =
    database.withConnection { implicit conn =>
      def benchmarks(taskId: Long): List[Benchmark] = {
        SQL"""select benchmarkId, idx from compilerBenchmarkTaskBenchmark
              where compilerBenchmarkTaskId = $taskId order by idx asc"""
          .as(SqlParser.long("benchmarkId").*)
          .map(benchmarkService.findById(_).get)
      }

      // Set[String] is spliced as a list -- using `mkString` wouldn't work.
      // See also http://stackoverflow.com/questions/9528273/in-clause-in-anorm
      val as = nextActions.map(_.entryName)
      SQL"""select * from compilerBenchmarkTask
            where nextAction in ($as)
            order by priority asc""".as(taskParser.*) map {
        case (id, priority, nextAction, scalaVersionId) =>
          CompilerBenchmarkTask(priority,
                                nextAction,
                                scalaVersionService.findById(scalaVersionId).get,
                                benchmarks(id))(Some(id))
      }
    }

  def update(id: Long, task: CompilerBenchmarkTask): Unit = database.withConnection {
    implicit conn =>
      val newScalaVersionId = scalaVersionService.getIdOrInsert(task.scalaVersion.copy()(None))
      SQL"""update compilerBenchmarkTask set
            priority = ${task.priority},
            nextAction = ${task.nextAction.entryName},
            scalaVersionId = $newScalaVersionId
          where id = $id""".executeUpdate()

      SQL"delete from compilerBenchmarkTaskBenchmark where compilerBenchmarkTaskId = $id"
        .executeUpdate()
      insertBenchmarks(id, task.benchmarks)
  }

  def delete(id: Long): Unit = database.withConnection { implicit conn =>
    // also deletes from compilerBenchmarkTask through `cascade` foreign key constraint
    SQL"delete from compilerBenchmarkTask where id = $id".executeUpdate()
  }
}
