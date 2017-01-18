package benchq

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

import anorm._
import play.api.Configuration
import play.api.db.Database

/**
 * Tools for database interaction from the REPL.
 *
 * Before leaving the REPL, make sure to stop the Play application (close connections), otherwise
 * subsequent `console` or `run` tasks will fail. See the `q` method in build.sbt.
 *
 * A very handy tool is `h2-browser` (run it in the sbt console).
 *
 * NOTE: REPL access doesn't work with an in-memory DB, as they are created per-classloader. The
 * REPL doesn't use that special `playCommonClassloader` (sbt hacks in the play plugin).
 */
class ToolDb(database: Database, config: Configuration) {
  def getOne: Int = database.withConnection { implicit conn =>
    SQL("select 2 as r").as(SqlParser.int("r").single)
  }

  lazy val dynamicParser: RowParser[Map[String, Any]] =
    SqlParser.folder(Map.empty[String, Any]) { (map, value, meta) =>
      Right(map + (meta.column.qualified -> value))
    }

  // scala> toolDb.query("select * from scalaVersion")
  def query(s: String): List[Map[String, Any]] = database.withConnection { implicit conn =>
    SQL(s).as(dynamicParser.*)
  }

  lazy val dbUrl = config.getString("db.default.url").getOrElse("")

  // scala> toolDb.shell("select * from scalaVersion")
  def shell(query: String): Unit = print(shellString(query))

  def shellString(query: String): String = {
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos)
    val shell = new org.h2.tools.Shell()
    shell.setOut(ps)
    shell.setErr(ps)
    shell.runTool("-url", dbUrl, "-sql", query)
    new String(baos.toByteArray, StandardCharsets.UTF_8)
  }
}
