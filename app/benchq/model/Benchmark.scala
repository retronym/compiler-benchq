package benchq
package model

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import play.api.db.Database

// Could be simplified, for example just a long string with the name and arguments, or create a
// unique name for each combination and keep the mapping to a specific benchmark implementation and
// its arguments elsewhere, (in the compiler-benchmarks repo).
// Keeping arguments here allows testing other combinations easily (for example custom compiler
// flags, e.g., when testing a new feature).
case class Benchmark(name: String, arguments: List[String], defaultBranches: List[Branch])(
    val id: Option[Long])

class BenchmarkService(database: Database) {

  private def updateArgsAndDefaults(id: Long, benchmark: Benchmark)(
      implicit conn: Connection): Unit = {
    SQL"delete from benchmarkArgument where benchmarkId = $id".executeUpdate()
    SQL"delete from defaultBenchmark where benchmarkId = $id".executeUpdate()

    for ((arg, idx) <- benchmark.arguments.iterator.zipWithIndex)
      SQL"insert into benchmarkArgument values ($id, $arg, $idx)"
        .executeInsert()
    for (b <- benchmark.defaultBranches)
      SQL"insert into defaultBenchmark (branch, benchmarkId) values (${b.entryName}, $id)"
        .executeInsert()
  }

  /**
   * Get the `id` of a [[Benchmark]], insert it if it doesn't exist yet.
   */
  def getIdOrInsert(benchmark: Benchmark): Long = database.withConnection { implicit conn =>
    def insert(): Long = {
      val id = SQL"insert into benchmark (name) values (${benchmark.name})"
        .executeInsert(scalar[Long].single)
      updateArgsAndDefaults(id, benchmark)
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
      .as(str("arg").*)
  }

  private def defaultBranchesFor(id: Long)(implicit conn: Connection): List[Branch] = {
    SQL"select branch from defaultBenchmark where benchmarkId = $id order by branch asc"
      .as(scalar[String].*)
      .map(Branch.withName)
  }

  def findById(id: Long): Option[Benchmark] = database.withConnection { implicit conn =>
    SQL"select name from benchmark where id = $id"
      .as(scalar[String].singleOpt)
      .map(Benchmark(_, argsForId(id), defaultBranchesFor(id))(Some(id)))
  }

  def findByName(name: String): List[Benchmark] = database.withConnection { implicit conn =>
    SQL"select id from benchmark where name = $name"
      .as(scalar[Long].*)
      .map(id => Benchmark(name, argsForId(id), defaultBranchesFor(id))(Some(id)))
  }

  val benchmarkParser = long("id") ~ str("name")

  def all(): List[Benchmark] = database.withConnection { implicit conn =>
    SQL"select * from benchmark"
      .as(benchmarkParser.*)
      .map {
        case id ~ name => Benchmark(name, argsForId(id), defaultBranchesFor(id))(Some(id))
      }
  }

  def update(id: Long, benchmark: Benchmark): Unit = database.withConnection { implicit conn =>
    SQL"update benchmark set name = ${benchmark.name} where id = $id".executeUpdate()
    updateArgsAndDefaults(id, benchmark)
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
