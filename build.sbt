name := "compiler-benchq"
organization := "org.scala-lang"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"

libraryDependencies ++= List(
  filters,
  jdbc,
  evolutions,
  ws,
  "com.typesafe.play"        %% "anorm"              % "2.5.0",
  "org.influxdb"             %  "influxdb-java"      % "2.5",
  "com.softwaremill.macwire" %% "macros"             % "2.2.5"  % "provided",
  "com.beachape"             %% "enumeratum"         % "1.5.6",
  "org.scalatestplus.play"   %% "scalatestplus-play" % "1.5.1"  % Test,
  "com.typesafe.akka"        %% "akka-testkit"       % "2.4.12" % Test)

// Access to components in console, interface with DBs
// When using the database in the REPL, need to call `q` before leaving the REPL. Otherwise the
// connection remains open, and a subsequent `run` or `console` won't be able to connect to the DB.
initialCommands in Compile in console :=
  """import play.api._, benchq._, queue._
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

TwirlKeys.templateImports += "benchq.queue._"

// Adds additional packages into Twirl
// TwirlKeys.templateImports += "org.scala-lang.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "org.scala-lang.binders._"
