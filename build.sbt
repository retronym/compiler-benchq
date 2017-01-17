name := "compiler-benchq"
organization := "org.scala-lang"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"

libraryDependencies ++= List(
  filters,
  jdbc,
  "com.typesafe.play"        %% "anorm"              % "2.5.0",
  "org.influxdb"             %  "influxdb-java"      % "2.5",
  "com.softwaremill.macwire" %% "macros"             % "2.2.5" % "provided",
  "org.scalatestplus.play"   %% "scalatestplus-play" % "1.5.1" % Test)

// access to components in console, interface with DBs
initialCommands in Compile in console :=
  """import play.api._, benchq._
    |val components = {
    |  val env = Environment.simple()
    |  val context = ApplicationLoader.createContext(env)
    |  new BenchQComponents(context)
    |}
    |import components._
  """.stripMargin

// Adds additional packages into Twirl
// TwirlKeys.templateImports += "org.scala-lang.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "org.scala-lang.binders._"
