package benchq
package queue

import anorm.SqlParser._
import anorm._
import enumeratum._
import play.api.db.Database

case class ScalaVersion(id: Option[Long], sha: String, compilerOptions: List[String])
class ScalaVersionService(database: Database) {
//  val scalaVersionParser: RowParser[(Option[Long], String)] =
//    get[Option[Long]]("scalaVersion.id") ~
//      get[String]("scalaVersion.sha") map {
//      case id ~ sha => (id, sha)
//    }

  def getOrInsertOption(option: String): Long = database.withConnection { implicit conn =>
    SQL"select id from compilerOption where opt = $option".as(scalar[Long].singleOpt) getOrElse {
      SQL"insert into compilerOption (opt) values ($option)".executeInsert(scalar[Long].single)
    }
  }

  def insert(scalaVersion: ScalaVersion): Unit = database.withConnection { implicit conn =>
    val versionId =
      SQL"insert into scalaVersion (sha) values (${scalaVersion.sha})".executeInsert()
    for (option <- scalaVersion.compilerOptions) {
      val optionId = getOrInsertOption(option)
      SQL"insert into scalaVersionCompilerOptions values ($versionId, $optionId)".executeInsert()
    }
  }
}

case class Benchmark(name: String, arguments: List[String])
sealed trait Action extends EnumEntry
object Action extends Enum[Action] {
  val values = findValues
  object CheckScalaVersionAvailable extends Action
  object StartScalaBuild extends Action
  object WaitForScalaBuild extends Action
  object StartBenchmark extends Action
  object WaitForBenchmark extends Action
}
case class BenchmarkTask(scalaVersion: ScalaVersion,
                         benchmarks: List[Benchmark],
                         nextAction: Action,
                         priority: Int)

class TaskQueue(toolDb: ToolDb) {}
