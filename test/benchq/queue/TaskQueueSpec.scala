package benchq
package queue

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import benchq.bench.BenchmarkRunner
import benchq.influxdb.ResultsDb
import benchq.jenkins.ScalaJenkins
import benchq.queue.Status._
import benchq.repo.ScalaBuildsRepo
import com.softwaremill.macwire._
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play.PlaySpec
import play.api.db.evolutions.Evolutions
import play.api.db.{Database, Databases}
import play.api.{ApplicationLoader, Environment}

import scala.collection.mutable
import scala.concurrent.Future

class TaskQueueSpec extends PlaySpec with BeforeAndAfterAll {

  class ScalaBuildsRepoMock extends ScalaBuildsRepo {
    override def checkBuildAvailable(scalaVersion: ScalaVersion): Future[Boolean] = {
      actions("checkBuildAvailable") = scalaVersion
      Future.successful(if (scalaVersion == v2_12_0) true else false)
    }
  }

  class BenchmarkRunnerMock extends BenchmarkRunner {
    override def startBenchmark(task: CompilerBenchmarkTask): Future[Unit] = {
      actions("startBenchmark") = task
      Future.successful(())
    }
  }

  class TestTaskQueue(compilerBenchmarkTaskService: CompilerBenchmarkTaskService,
                      benchmarkResultService: BenchmarkResultService,
                      benchmarkRunner: BenchmarkRunner,
                      scalaBuildsRepo: ScalaBuildsRepo,
                      scalaJenkins: ScalaJenkins,
                      resultsDb: ResultsDb,
                      system: ActorSystem)
      extends TaskQueue(compilerBenchmarkTaskService,
                        benchmarkResultService,
                        benchmarkRunner,
                        scalaBuildsRepo,
                        scalaJenkins,
                        resultsDb,
                        system) {
    class TestQueueActor(probeActorRef: ActorRef) extends QueueActor {
      override def receive: Receive = {
        case msg: Any =>
          super.receive(msg)
          probeActorRef ! msg
      }
    }
  }

  lazy val components = {
    val env = Environment.simple()
    val context = ApplicationLoader.createContext(env)
    new BenchQComponents(context) {
      override lazy val database: Database = Databases.inMemory()
      override lazy val scalaBuildsRepo: ScalaBuildsRepo = wire[ScalaBuildsRepoMock]
      override lazy val benchmarkRunner: BenchmarkRunner = wire[BenchmarkRunnerMock]
      override lazy val taskQueue: TestTaskQueue = wire[TestTaskQueue]
    }
  }
  import components._
  val probe = TestProbe()(actorSystem)
  val testActor =
    actorSystem.actorOf(Props(new taskQueue.TestQueueActor(probe.ref)), "test-queue-actor")

  override def beforeAll(): Unit = {
    Evolutions.applyEvolutions(database)
  }

  override def afterAll(): Unit = {
    database.shutdown()
  }

  val v2_12_0 = ScalaVersion("8684ae833dcfeac6107343fcca5501301e509eef", Nil)(None)
  val bench =
    Benchmark("scala.tools.nsc.HotScalacBenchmark", List("source=better-files"))(None)
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
    }
  }

}
