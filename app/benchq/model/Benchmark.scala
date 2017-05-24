package benchq
package model

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import play.api.db.Database

case class Benchmark(command: String, defaultBranches: List[Branch])(val id: Option[Long]) {
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
      val id = SQL"insert into benchmark (command) values (${benchmark.command})"
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
    SQL"select command from benchmark where id = $id"
      .as(scalar[String].singleOpt)
      .map(Benchmark(_, defaultBranchesFor(id))(Some(id)))
  }

  def findByCommand(command: String): List[Benchmark] = database.withConnection { implicit conn =>
    SQL"select id from benchmark where command = $command"
      .as(scalar[Long].*)
      .map(id => Benchmark(command, defaultBranchesFor(id))(Some(id)))
  }

  val benchmarkParser = long("id") ~ str("command")

  def all(): List[Benchmark] = database.withConnection { implicit conn =>
    SQL"select * from benchmark"
      .as(benchmarkParser.*)
      .map {
        case id ~ command => Benchmark(command, defaultBranchesFor(id))(Some(id))
      }
  }

  def update(id: Long, benchmark: Benchmark): Unit = database.withConnection { implicit conn =>
    SQL"update benchmark set command = ${benchmark.command} where id = $id".executeUpdate()
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
