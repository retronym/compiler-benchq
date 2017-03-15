package benchq
package queue

import akka.actor.{ActorRef, Props}
import akka.testkit.TestProbe
import benchq.git.GitRepo
import benchq.influxdb.ResultsDb
import benchq.jenkins.ScalaJenkins
import benchq.model.Status._
import benchq.model._
import benchq.repo.ScalaBuildsRepo
import com.softwaremill.macwire._
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.PlaySpec
import play.api.db.evolutions.Evolutions
import play.api.db.{Database, Databases}
import play.api.libs.ws.WSClient
import play.api.{ApplicationLoader, Environment}

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Success

// TODO: improve coverage

class TaskQueueSpec extends PlaySpec with BeforeAndAfterAll {

  class ScalaBuildsRepoMock(ws: WSClient, config: Config) extends ScalaBuildsRepo(ws, config) {
    override def checkBuildAvailable(scalaVersion: ScalaVersion): Future[Option[String]] = {
      actions("checkBuildAvailable") = scalaVersion
      Future.successful(if (scalaVersion == v2_12_0) Some("2.12.0") else None)
    }
  }

  class ScalaJenkinsMock(ws: WSClient,
                         config: Config,
                         scalaBuildsRepo: ScalaBuildsRepo,
                         gitRepo: GitRepo)
      extends ScalaJenkins(ws, config, scalaBuildsRepo, gitRepo) {
    override def startScalaBuild(task: CompilerBenchmarkTask): Future[Unit] = {
      actions("startScalaBuild") = task
      Future.successful(())
    }

    override def startBenchmark(task: CompilerBenchmarkTask): Future[Unit] = {
      actions("startBenchmark") = task
      Future.successful(())
    }
  }

  class ResultsDbMock(config: Config) extends ResultsDb(config) {
    override def sendResults(task: CompilerBenchmarkTask,
                             results: List[BenchmarkResult]): Future[Unit] = {
      actions("sendResults") = results
      Future.successful(())
    }
  }

  lazy val components = {
    val env = Environment.simple()
    val context = ApplicationLoader.createContext(env)
    new BenchQComponents(context) {
      override lazy val database: Database = Databases.inMemory()
      override lazy val scalaBuildsRepo: ScalaBuildsRepo = wire[ScalaBuildsRepoMock]
      override lazy val scalaJenkins: ScalaJenkins = wire[ScalaJenkinsMock]
      override lazy val resultsDb: ResultsDb = wire[ResultsDbMock]
    }
  }
  import components._
  import taskQueue.QueueActor._

  class TestQueueActor(probeActorRef: ActorRef) extends taskQueue.QueueActor {
    override def receive: Receive = {
      case msg: Any =>
        super.receive(msg)
        probeActorRef ! msg
    }
  }

  val probe = TestProbe()(actorSystem)
  val testActor =
    actorSystem.actorOf(Props(new TestQueueActor(probe.ref)), "test-queue-actor")

  override def beforeAll(): Unit = {
    Evolutions.applyEvolutions(database)
  }

  override def afterAll(): Unit = {
    database.shutdown()
  }

  val v2_12_0 =
    ScalaVersion(ScalaVersion.scalaScalaRepo, "8684ae833dcfeac6107343fcca5501301e509eef", Nil)(
      None)
  val bench =
    Benchmark("scala.tools.nsc.HotScalacBenchmark",
              List("source=better-files"),
              Branch.sortedValues)(None)
  val task =
    CompilerBenchmarkTask(1, CheckScalaVersionAvailable, v2_12_0, List(bench))(None)

  val actions = mutable.Map.empty[String, AnyRef]

  "QueueActor" should {
    "move a Task through the queue" in {
      val id = compilerBenchmarkTaskService.insert(task)
      testActor ! PingQueue

      // Testing the messages sent around is maybe not that essential, but without it the
      // functional test below (updates of the task in the database) fails, because the queue
      // actor runs the updates as concurrent tasks (using Futures), and we need to await them.
      probe.expectMsg(PingQueue)
      probe.expectMsgType[ScalaVersionAvailable]
      probe.expectMsg(PingQueue)
      probe.expectMsgType[BenchmarkStarted]

      compilerBenchmarkTaskService.findById(id) mustEqual Some(
        task.copy(status = WaitForBenchmark)(None))
      actions("checkBuildAvailable") mustBe v2_12_0
      actions("startBenchmark") mustBe task.copy(status = StartBenchmark)(None)

      val results = List(BenchmarkResult(id, bench, Map.empty))
      testActor ! BenchmarkFinished(id, Success(results))

      probe.expectMsgType[BenchmarkFinished]
      probe.expectMsg(PingQueue)
      probe.expectMsgType[ResultsSent]

      compilerBenchmarkTaskService.findById(id) mustEqual Some(task.copy(status = Done)(None))
      actions("sendResults") mustBe Nil // getting results from the DB not yet implemented
    }
  }
}
