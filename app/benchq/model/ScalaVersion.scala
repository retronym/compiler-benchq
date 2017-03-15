package benchq
package model

import java.sql.Connection

import anorm.SqlParser._
import anorm._
import play.api.db.Database

case class ScalaVersion(repo: String, sha: String, compilerOptions: List[String])(val id: Option[Long]) {
  override def toString = {
    val opts = if (compilerOptions.isEmpty) "" else " " + compilerOptions.mkString("", " ", "")
    s"$repo#$sha$opts"
  }
}
object ScalaVersion {
  val scalaScalaRepo = "scala/scala"
}

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
      val id = SQL"insert into scalaVersion (repo, sha) values (${scalaVersion.repo}, ${scalaVersion.sha})"
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
      .as(str("opt").*)
  }

  def findById(id: Long): Option[ScalaVersion] = database.withConnection { implicit conn =>
    SQL"select repo, sha from scalaVersion where id = $id"
      .as((str("repo") ~ str("sha")).singleOpt)
      .map {
        case r ~ s => ScalaVersion(r, s, optsForId(id))(Some(id))
      }
  }

  def findBySha(sha: String): List[ScalaVersion] = database.withConnection { implicit conn =>
    SQL"select id, repo from scalaVersion where sha = $sha"
      .as((long("id") ~ str("repo")).*)
      .map {
        case id ~ repo => ScalaVersion(repo, sha, optsForId(id))(Some(id))
      }
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
