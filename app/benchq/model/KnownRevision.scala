package benchq
package model

import anorm.SqlParser._
import anorm._
import enumeratum._
import play.api.db.Database

sealed abstract class Branch(override val entryName: String) extends EnumEntry {
  override def toString = entryName
}

object Branch extends Enum[Branch] {
  val values = findValues
  case object v2_11_x extends Branch("2.11.x")
  case object v2_12_x extends Branch("2.12.x")
  case object v2_13_x extends Branch("2.13.x")
}

case class KnownRevision(branch: Branch, revision: String)

class KnownRevisionService(database: Database) {
  val knownRevisionParser = str("branch") ~ str("revision") map {
    case b ~ r => KnownRevision(Branch.withName(b), r)
  }

  def lastKnownRevision(branch: Branch): Option[KnownRevision] = database.withConnection {
    implicit conn =>
      SQL"select * from knownRevision where branch = ${branch.entryName}"
        .as(knownRevisionParser.singleOpt)
  }

  def updateOrInsert(knownRevision: KnownRevision): Unit = database.withConnection {
    implicit conn =>
      val changedRows =
        SQL"""update knownRevision
              set revision = ${knownRevision.revision}
              where branch = ${knownRevision.branch.entryName}""".executeUpdate()
      if (changedRows == 0)
        SQL"""insert into knownRevision
              values (${knownRevision.branch.entryName}, ${knownRevision.revision})"""
          .executeInsert()
  }
}
