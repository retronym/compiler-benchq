package benchq

import play.api.db.Database
import anorm._

class ToolDb(database: Database) {
  def getOne: Int = database.withConnection { implicit conn =>
    SQL("select 2 as r").as(SqlParser.int("r").single)
  }
}
