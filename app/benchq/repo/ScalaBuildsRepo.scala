package benchq
package repo

import benchq.git.GitRepo
import benchq.model.ScalaVersion
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.{WSAuthScheme, WSClient}

import scala.concurrent.Future

class ScalaBuildsRepo(ws: WSClient, config: Config, gitRepo: GitRepo) {
  import config.scalaBuildsRepo._

  def repoFor(scalaVersion: ScalaVersion): String = {
    if (scalaVersion.repo == config.scalaScalaRepo && gitRepo.isMerged(scalaVersion.sha))
      integrationRepo
    else
      tempRepo
  }

  // $match expressions don't support regular expressions, just * and ?
  // https://www.jfrog.com/confluence/display/RTF/Artifactory+Query+Language
  def searchQuery(scalaVersion: ScalaVersion) =
    s"""items.find({
       |  "repo":"${repoFor(scalaVersion)}",
       |  "name":{"$$match":"scala-compiler*${scalaVersion.sha.take(7)}.jar"}
       |})
     """.stripMargin

  def checkBuildAvailable(scalaVersion: ScalaVersion): Future[Option[String]] = {
    // https://www.jfrog.com/confluence/display/RTF/Artifactory+REST+API#ArtifactoryRESTAPI-ArtifactoryQueryLanguage(AQL)
    // Example response:
    // { "results": [
    //   {
    //     "repo": "scala-integration",
    //     "path": "org/scala-lang/scala-compiler/2.12.2-bin-a21daec",
    //     "name": "scala-compiler-2.12.2-bin-a21daec.jar",
    //     ...
    //   }],
    //   "range": { "start_pos": 0, "end_pos": 1, "total": 1 }
    // }
    Logger.info(s"Checking if Scala build is available for $scalaVersion")
    ws.url(baseUrl + "api/search/aql")
      .withAuth(user, password, WSAuthScheme.BASIC)
      .post(searchQuery(scalaVersion))
      .map(response =>
        (response.json \ "range" \ "total").as[Int] match {
          case 1 =>
            Some(((response.json \ "results")(0) \ "path").as[String].split('/').last)
          case 0 =>
            None
          case n => // fail the future
            throw new Exception(s"Expected 0 or 1 result searching for $scalaVersion, got $n")
      })
  }
}
