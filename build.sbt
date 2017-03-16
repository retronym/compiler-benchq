name := "compiler-benchq"
organization := "org.scala-lang"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"

scalacOptions ++= List(
  "-deprecation",
  "-Xfatal-warnings",
  "-Xlint")

// jwt-core, a dep of silhouette, https://github.com/mohiva/play-silhouette-seed/issues/20#issuecomment-75306712
resolvers += "Atlassian Releases" at "https://maven.atlassian.com/public/"

libraryDependencies ++= List(
  filters,
  jdbc,
  evolutions,
  ws,
  cache,
  "com.typesafe.play"        %% "anorm"                       % "2.5.0",
  "org.influxdb"             %  "influxdb-java"               % "2.5",
  "com.softwaremill.macwire" %% "macros"                      % "2.2.5"       % "provided",
  "com.beachape"             %% "enumeratum"                  % "1.5.6",
  "com.github.pathikrit"     %% "better-files"                % "2.17.1",
  "com.adrianhurt"           %% "play-bootstrap"              % "1.1-P25-B3",
  "com.mohiva"               %% "play-silhouette"             % "4.0.0",
  "com.mohiva"               %% "play-silhouette-crypto-jca"  % "4.0.0",
  "com.mohiva"               %% "play-silhouette-persistence" % "4.0.0",
  "com.iheart"               %% "ficus"                       % "1.4.0",
  "org.scalatestplus.play"   %% "scalatestplus-play"          % "1.5.1"       % Test,
  "com.typesafe.akka"        %% "akka-testkit"                % "2.4.12"      % Test)

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

TwirlKeys.templateImports ++= List(
  "benchq.model._",
  "benchq.RevRouteFix",
  "play.api.mvc.Flash",
  "play.api.mvc.Call")

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "org.scala-lang.binders._"

// sbt-assembly

assemblyJarName in assembly := "benchq.jar"
mainClass in assembly := Some("play.core.server.ProdServerStart")
test in assembly := {}

// assets
fullClasspath in assembly += Attributed.blank(PlayKeys.playPackageAssets.value)

// exclude commons-logging
//   - http://stackoverflow.com/questions/36227554/slf4j-causing-multiple-class-name-conflicts-with-sbt-assembly
//   - https://github.com/sbt/sbt-assembly#exclude-specific-transitive-deps
libraryDependencies ~= { _ map {
  case m if m.organization == "com.typesafe.play" =>
    m.exclude("commons-logging", "commons-logging")
  case m => m
}}

assemblyMergeStrategy in assembly := {
  // play-silhouette.jar and this project both have `messages`
  case "messages" => MergeStrategy.concat
  // exists in multiple jars
  case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.discard
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}
