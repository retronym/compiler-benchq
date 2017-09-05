package benchq
package model

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import play.api.db.Database

case class Benchmark(command: String, defaultBranches: List[Branch], daily: Int)(val id: Option[Long]) {
  override def toString = command
}

class BenchmarkService(database: Database) {

  private def updateDefaults(id: Long, benchmark: Benchmark)(implicit conn: Connection): Unit = {
    SQL"delete from defaultBenchmark where benchmarkId = $id".executeUpdate()

    for (b <- benchmark.defaultBranches)
      SQL"insert into defaultBenchmark (branch, benchmarkId) values (${b.entryName}, $id)"
        .executeInsert()
  }

  /**
   * Get the `id` of a [[Benchmark]], insert it if it doesn't exist yet.
   */
  def getIdOrInsert(benchmark: Benchmark): Long = database.withConnection { implicit conn =>
    def insert(): Long = {
      val id = SQL"insert into benchmark (command, daily) values (${benchmark.command}, ${benchmark.daily})"
        .executeInsert(scalar[Long].single)
      updateDefaults(id, benchmark)
      id
    }

    benchmark.id.getOrElse {
      findByCommand(benchmark.command)
        .find(_ == benchmark)
        .flatMap(_.id)
        .getOrElse(insert())
    }
  }

  private def defaultBranchesFor(id: Long)(implicit conn: Connection): List[Branch] = {
    SQL"select branch from defaultBenchmark where benchmarkId = $id order by branch asc"
      .as(scalar[String].*)
      .map(Branch.withName)
  }

  def findById(id: Long): Option[Benchmark] = database.withConnection { implicit conn =>
    SQL"select command, daily from benchmark where id = $id"
      .as((str("command") ~ int("daily")).singleOpt)
      .map {
        case c ~ d => Benchmark(c, defaultBranchesFor(id), d)(Some(id))
      }
  }

  def findByCommand(command: String): List[Benchmark] = database.withConnection { implicit conn =>
    SQL"select id, daily from benchmark where command = $command"
      .as((long("id") ~ int("daily")).*)
      .map {
        case id ~ d => Benchmark(command, defaultBranchesFor(id), d)(Some(id))
      }
  }

  val benchmarkParser = long("id") ~ str("command") ~ int("daily")

  def all(): List[Benchmark] = database.withConnection { implicit conn =>
    SQL"select * from benchmark order by id"
      .as(benchmarkParser.*)
      .map {
        case id ~ command ~ daily => Benchmark(command, defaultBranchesFor(id), daily)(Some(id))
      }
  }

  def update(id: Long, benchmark: Benchmark): Unit = database.withConnection { implicit conn =>
    SQL"update benchmark set command = ${benchmark.command}, daily = ${benchmark.daily} where id = $id".executeUpdate()
    updateDefaults(id, benchmark)
  }

  def delete(id: Long): Unit = database.withConnection { implicit conn =>
    // also deletes from benchmarkArgument, `cascade` foreign key constraint
    SQL"delete from benchmark where id = $id".executeUpdate()
  }

  def defaultBenchmarks(branch: Branch): List[Benchmark] = database.withConnection {
    implicit conn =>
      SQL"select benchmarkId from defaultBenchmark where branch = ${branch.entryName}"
        .as(scalar[Long].*)
        .flatMap(findById)
  }
}
