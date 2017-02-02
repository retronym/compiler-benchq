name := "compiler-benchq"
organization := "org.scala-lang"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala, DebianPlugin)

scalaVersion := "2.11.8"

libraryDependencies ++= List(
  filters,
  jdbc,
  evolutions,
  ws,
  cache,
  "com.typesafe.play"        %% "anorm"              % "2.5.0",
  "org.influxdb"             %  "influxdb-java"      % "2.5",
  "com.softwaremill.macwire" %% "macros"             % "2.2.5"  % "provided",
  "com.beachape"             %% "enumeratum"         % "1.5.6",
  "com.github.pathikrit"     %% "better-files"       % "2.17.1",
  "com.adrianhurt"           %% "play-bootstrap"     % "1.1-P25-B3",
  "org.pac4j"                %  "play-pac4j"         % "2.6.2",
  "org.pac4j"                %  "pac4j-oauth"        % "1.9.5",
  "org.scalatestplus.play"   %% "scalatestplus-play" % "1.5.1"  % Test,
  "com.typesafe.akka"        %% "akka-testkit"       % "2.4.12" % Test)

// Access to components in console, interface with DBs
// When using the database in the REPL, need to call `q` before leaving the REPL. Otherwise the
// connection remains open, and a subsequent `run` or `console` won't be able to connect to the DB.
initialCommands in Compile in console :=
  """import play.api._, benchq._, model._, Status._
    |import play.api.libs.concurrent.Execution.Implicits.defaultContext
    |val components = {
    |  val env = Environment.simple()
    |  val context = ApplicationLoader.createContext(env)
    |  new BenchQComponents(context)
    |
    |}
    |import components._
    |def q = Play.stop(application)
  """.stripMargin

TwirlKeys.templateImports ++= List("benchq.model._", "benchq.RevRouteFix", "play.api.mvc.Flash", "play.api.mvc.Call")

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "org.scala-lang.binders._"

maintainer in Linux := "Scala Team <scala-team@lightbend.com>"
packageSummary in Linux := "Scala BenchQ"
packageDescription := "Scala BenchQ"
