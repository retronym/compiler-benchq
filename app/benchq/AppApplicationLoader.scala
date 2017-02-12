package benchq

import benchq.git.GitRepo
import benchq.influxdb.ResultsDb
import benchq.jenkins.ScalaJenkins
import benchq.model._
import benchq.queue._
import benchq.repo.ScalaBuildsRepo
import com.mohiva.play.silhouette.api.util.{Clock, PlayHTTPLayer}
import com.mohiva.play.silhouette.crypto.{JcaCookieSigner, JcaCookieSignerSettings}
import com.mohiva.play.silhouette.impl.providers.OAuth2Settings
import com.mohiva.play.silhouette.impl.providers.oauth2.GitHubProvider
import com.mohiva.play.silhouette.impl.providers.oauth2.state.{CookieStateProvider, CookieStateSettings}
import com.mohiva.play.silhouette.impl.util.SecureRandomIDGenerator
import com.softwaremill.macwire._
import controllers.{Assets, HomeController}
import play.api.ApplicationLoader.Context
import play.api._
import play.api.cache.EhCacheComponents
import play.api.db.evolutions.EvolutionsComponents
import play.api.db.{DBComponents, Database, HikariCPComponents}
import play.api.i18n.I18nComponents
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.routing.Router
import router.Routes

class AppApplicationLoader extends ApplicationLoader {
  def load(context: Context): Application = {
    // https://www.playframework.com/documentation/2.5.x/ScalaCompileTimeDependencyInjection
    LoggerConfigurator(context.environment.classLoader).foreach {
      _.configure(context.environment)
    }

    val components = new BenchQComponents(context)
    // Workaround to make reverse routing work with a prefix, see
    // https://github.com/playframework/playframework/issues/4977#issuecomment-135486198
    components.configuration
      .getString("play.http.context")
      .foreach(_root_.router.RoutesPrefix.setPrefix)
    components.applicationEvolutions // force the lazy val to ensure evolutions are applied
    components.application
  }
}

class BenchQComponents(context: Context)
    extends BuiltInComponentsFromContext(context)
    with DBComponents
    with HikariCPComponents
    with EvolutionsComponents
    with AhcWSComponents
    with I18nComponents
    with EhCacheComponents {
  lazy val assets: Assets = wire[Assets]
  lazy val router: Router = {
    // The default constructor of Routes takes a prefix, so it needs to be in scope. However, the
    // call to `withPrefix` below is still necessary for the prefix to be picked up in reverse
    // routes (often used for redirects)
    lazy val prefix: String = httpConfiguration.context
    wire[Routes].withPrefix(prefix)
  }
  lazy val database: Database = dbApi.database("default")

  lazy val toolDb: ToolDb = wire[ToolDb]
  lazy val resultsDb: ResultsDb = wire[ResultsDb]

  lazy val config: Config = wire[Config]
  lazy val taskQueue: TaskQueue = wire[TaskQueue]
  lazy val gitRepo: GitRepo = wire[GitRepo]
  lazy val scalaJenkins: ScalaJenkins = wire[ScalaJenkins]
  lazy val scalaBuildsRepo: ScalaBuildsRepo = wire[ScalaBuildsRepo]

  lazy val scalaVersionService: ScalaVersionService = wire[ScalaVersionService]
  lazy val benchmarkService: BenchmarkService = wire[BenchmarkService]
  lazy val compilerBenchmarkTaskService: CompilerBenchmarkTaskService =
    wire[CompilerBenchmarkTaskService]
  lazy val benchmarkResultService: BenchmarkResultService = wire[BenchmarkResultService]
  lazy val knownRevisionService: KnownRevisionService = wire[KnownRevisionService]

  lazy val githubAuth: GitHubProvider = {
    import config.Silhouette._
    // ExecutionContext, used for PlayHTTPLayer, SecureRandomIDGenerator
    implicit val ec = play.api.libs.concurrent.Execution.Implicits.defaultContext
    val httpLayer = {
      wire[PlayHTTPLayer]
    }
    val stateProvider: CookieStateProvider = {
      val settings = CookieStateSettings(secureCookie = false) // disable for testing without ssl
      val idGenerator = new SecureRandomIDGenerator
      val jcacookieSigner = new JcaCookieSigner(JcaCookieSignerSettings(cookieSignerKey))
      val clock = Clock()
      wire[CookieStateProvider]
    }
    val settings = OAuth2Settings(
      authorizationURL = Some(githubAuthorizationURL),
      accessTokenURL = githubAccessTokenURL,
      redirectURL = githubRedirectURL,
      clientID = githubClientID,
      clientSecret = githubClientSecret
    )
    wire[GitHubProvider]
  }

  lazy val homeController: HomeController = wire[HomeController]
  lazy val webhooks: Webhooks = wire[Webhooks]
}
