package benchq
package repo

import benchq.model.ScalaVersion
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.ws.{WSAuthScheme, WSClient}

import scala.concurrent.Future

class ScalaBuildsRepo(ws: WSClient, config: Config) {
  import config.ScalaBuildsRepo._

  def searchQuery(scalaVersion: ScalaVersion) =
    s"""items.find({
       |  "repo":"$repo",
       |  "name":{"$$match":"scala-compiler*${scalaVersion.sha.take(7)}-nightly.jar"}
       |})
     """.stripMargin

  def checkBuildAvailable(scalaVersion: ScalaVersion): Future[Option[String]] = {
    // https://www.jfrog.com/confluence/display/RTF/Artifactory+REST+API#ArtifactoryRESTAPI-ArtifactoryQueryLanguage(AQL)
    // Example response:
    // { "results": [
    //   {
    //     "repo": "scala-integration",
    //     "path": "org/scala-lang/scala-compiler/2.12.2-b9d4089-nightly",
    //     "name": "scala-compiler-2.12.2-b9d4089-nightly.jar",
    //     ...
    //   }],
    //   "range": { "start_pos": 0, "end_pos": 1, "total": 1 }
    // }
    Logger.info(s"Checking if Scala build is available for $scalaVersion")
    ws.url(host + "/search/aql")
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
