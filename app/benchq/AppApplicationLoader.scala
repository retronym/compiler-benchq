package benchq

import benchq.git.GitRepo
import benchq.influxdb.ResultsDb
import benchq.jenkins.ScalaJenkins
import benchq.model._
import benchq.queue._
import benchq.repo.ScalaBuildsRepo
import benchq.security._
import com.mohiva.play.silhouette.api.actions._
import com.mohiva.play.silhouette.api.crypto.Base64AuthenticatorEncoder
import com.mohiva.play.silhouette.api.repositories.{AuthInfoRepository, AuthenticatorRepository}
import com.mohiva.play.silhouette.api.services.AuthenticatorService
import com.mohiva.play.silhouette.api.util.{Clock, PlayHTTPLayer}
import com.mohiva.play.silhouette.api.{Environment => SilhouetteEnvironment, _}
import com.mohiva.play.silhouette.crypto.{JcaCookieSigner, JcaCookieSignerSettings}
import com.mohiva.play.silhouette.impl.authenticators._
import com.mohiva.play.silhouette.impl.providers.oauth2.state.{CookieStateProvider, CookieStateSettings}
import com.mohiva.play.silhouette.impl.providers.{OAuth2Info, OAuth2Settings, SocialProviderRegistry}
import com.mohiva.play.silhouette.impl.util.{DefaultFingerprintGenerator, SecureRandomIDGenerator}
import com.mohiva.play.silhouette.persistence.daos.InMemoryAuthInfoDAO
import com.mohiva.play.silhouette.persistence.repositories.DelegableAuthInfoRepository
import com.softwaremill.macwire._
import controllers.{Assets, HomeController, SocialAuthController}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import play.api.ApplicationLoader.Context
import play.api._
import play.api.cache.EhCacheComponents
import play.api.db.evolutions.EvolutionsComponents
import play.api.db.{DBComponents, Database, HikariCPComponents}
import play.api.i18n.I18nComponents
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.routing.Router
import router.Routes

import scala.concurrent.ExecutionContext

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
    with EhCacheComponents
    with SecurityComponents {
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
  lazy val lastExecutedBenchmarkService: LastExecutedBenchmarkService = wire[LastExecutedBenchmarkService]

  lazy val socialAuthController: SocialAuthController = wire[SocialAuthController]
  lazy val homeController: HomeController = wire[HomeController]
  lazy val webhooks: Webhooks = wire[Webhooks]
}

trait SecurityComponents {
  def configuration: Configuration
  def config: Config
  def wsClient: WSClient

  // format: off

  implicit lazy val ec: ExecutionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext

  lazy val gitHubProvider: CustomGithubProvider = wire[CustomGithubProvider]
    lazy val httpLayer: PlayHTTPLayer = wire[PlayHTTPLayer]
    lazy val stateProvider: CookieStateProvider = wire[CookieStateProvider]
      lazy val cookieStateSettings = CookieStateSettings(secureCookie = false) // disable for testing without ssl
      lazy val idGenerator = new SecureRandomIDGenerator
      lazy val jcaCookieSigner = new JcaCookieSigner(JcaCookieSignerSettings(config.silhouette.cookieSignerKey))
      lazy val clock = Clock()
    lazy val oauth2Settings: OAuth2Settings = configuration.underlying.as[OAuth2Settings]("silhouette.github")


  lazy val silhouette: Silhouette[DefaultEnv] = wire[SilhouetteProvider[DefaultEnv]]
    lazy val silhouetteEnvironment: SilhouetteEnvironment[DefaultEnv] = SilhouetteEnvironment[DefaultEnv](userService, authenticatorService, requestProviders, eventBus)
      lazy val userService: UserService = wire[UserService]
      lazy val authenticatorService: AuthenticatorService[CookieAuthenticator] = wire[CookieAuthenticatorService]
        lazy val cookieAuthenticatorSettings: CookieAuthenticatorSettings = configuration.underlying.as[CookieAuthenticatorSettings]("silhouette.authenticator.cookie")
        lazy val repo: Option[AuthenticatorRepository[CookieAuthenticator]] = None
        // jcaCookieSigner from above
        lazy val authenticatorEncoder = new Base64AuthenticatorEncoder()
        lazy val fingerprintGenerator = new DefaultFingerprintGenerator()
        // idGenerator from above
        // clock from above
      lazy val requestProviders: Seq[RequestProvider] = Seq()
      lazy val eventBus: EventBus = EventBus()
    lazy val securedAction: SecuredAction = {
      val securedErrorHandler: SecuredErrorHandler = wire[CustomSecuredErrorHandler]
      new DefaultSecuredAction(new DefaultSecuredRequestHandler(securedErrorHandler))
    }
    lazy val unsecuredAction: UnsecuredAction = {
      val unSecuredErrorHandler: UnsecuredErrorHandler = wire[CustomUnsecuredErrorHandler]
      new DefaultUnsecuredAction(new DefaultUnsecuredRequestHandler(unSecuredErrorHandler))
    }
    lazy val userAwareAction = new DefaultUserAwareAction(new DefaultUserAwareRequestHandler)

  lazy val authInfoRepository: AuthInfoRepository = new DelegableAuthInfoRepository(oauth2InfoDAO)
    lazy val oauth2InfoDAO = wire[InMemoryAuthInfoDAO[OAuth2Info]]

  lazy val socialProviderRegistry: SocialProviderRegistry = SocialProviderRegistry(List(gitHubProvider))

  // format: on
}
