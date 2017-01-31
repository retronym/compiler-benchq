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
      .as(str("arg").*)
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

  val benchmarkParser = long("id") ~ str("name")

  def all(): List[Benchmark] = database.withConnection { implicit conn =>
    SQL"select * from benchmark"
      .as(benchmarkParser.*)
      .map {
        case id ~ name => Benchmark(name, argsForId(id))(Some(id))
      }
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
