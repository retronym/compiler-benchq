package benchq.model

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import play.api.db.Database

case class LastExecutedBenchmark(benchmarkId: Long, branch: Branch, sha: String, commitTime: Long)

class LastExecutedBenchmarkService(database: Database) {
  def updateOrInsert(last: LastExecutedBenchmark): Unit = database.withConnection { implicit conn =>
    import last._
    val changedRows =
      SQL"""update lastExecutedBenchmark
            set sha = $sha, commitTime = $commitTime
            where benchmarkId = $benchmarkId and branch = ${branch.entryName}""".executeUpdate()
    if (changedRows == 0)
      SQL"""insert into lastExecutedBenchmark
            values ($benchmarkId, ${branch.entryName}, $sha, $commitTime)""".executeInsert()
  }

  def findLast(benchmarkId: Long, branch: Branch): Option[LastExecutedBenchmark] =
    database.withConnection { implicit conn =>
      SQL"select sha, commitTime from lastExecutedBenchmark where benchmarkId = $benchmarkId and branch = ${branch.entryName}"
        .as((str("sha") ~ long("commitTime")).singleOpt)
        .map {
          case s ~ t => LastExecutedBenchmark(benchmarkId, branch, s, t)
        }
    }
}
