package benchq

import benchq.queue.Status._
import benchq.queue._
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play._
import play.api._
import play.api.db.evolutions.Evolutions
import play.api.db.{Database, Databases}

class ModelSpec extends PlaySpec with BeforeAndAfterAll {
  lazy val components = {
    val env = Environment.simple()
    val context = ApplicationLoader.createContext(env)
    new BenchQComponents(context) {
      override lazy val database: Database = Databases.inMemory()
    }
  }
  import components._

  val v2_12_0 = ScalaVersion("8684ae833dcfeac6107343fcca5501301e509eef", Nil)(None)
  val v2_12_1 = ScalaVersion("2787b47396013a44072fa7321482103b66fbccd3", Nil)(None)
  val v2_12_1_noForw = v2_12_1.copy(compilerOptions = List("-Xmixin-force-forwarders:junit"))(None)

  val hotBetter =
    Benchmark("scala.tools.nsc.HotScalacBenchmark", List("source=better-files"))(None)
  val hotBetterNoForw = hotBetter.copy(
    arguments = hotBetter.arguments ::: List("extraArgs=-Xmixin-force-forwarders:junit"))(None)

  val task1 =
    CompilerBenchmarkTask(1, WaitForScalaBuild, v2_12_0, List(hotBetter, hotBetterNoForw))(
      None)
  val task2 = task1.copy(scalaVersion = v2_12_1)(None)
  val task3 = task1.copy(scalaVersion = v2_12_1_noForw)(None)
  val task4 = task1.copy(priority = 10, status = StartBenchmark)(None)
  val task5 = task1.copy(priority = 20, status = CheckScalaVersionAvailable)(None)
  val allTasks = Set(task1, task2, task3, task4, task5)

  override def beforeAll(): Unit = {
    Evolutions.applyEvolutions(database)
    allTasks.foreach(compilerBenchmarkTaskService.insert)
  }

  override def afterAll(): Unit = {
    database.shutdown()
  }

  "CompilerBenchmarkTask model" should {
    "find scala version by sha" in {
      scalaVersionService
        .findBySha(v2_12_0.sha) must contain theSameElementsAs List(v2_12_0)
      scalaVersionService
        .findBySha(v2_12_1.sha) must contain theSameElementsAs List(v2_12_1, v2_12_1_noForw)
    }

    "return all tasks sorted by priority" in {
      val ts = compilerBenchmarkTaskService.byPriority()
      ts must contain theSameElementsAs allTasks
      ts.map(_.priority) mustBe sorted
    }

    "return tasks matching a permitted status" in {
      compilerBenchmarkTaskService.byPriority(Set(StartBenchmark)) must
        contain theSameElementsAs Set(task4)

      compilerBenchmarkTaskService.byPriority(Set(WaitForScalaBuild)) must
        contain theSameElementsAs Set(task1, task2, task3)
    }

    "delete ScalaVersion and their arguments" in {
      val sv = v2_12_0.copy(compilerOptions = List("-my-option"))(None)
      val id = scalaVersionService.getIdOrInsert(sv)

      def queryOpts = toolDb.query("select * from compilerOption where opt = '-my-option'")

      scalaVersionService.findById(id) mustEqual Some(sv)
      queryOpts must have length 1

      scalaVersionService.delete(id)

      scalaVersionService.findById(id) mustBe empty
      queryOpts mustBe empty
    }

    "delete Benchmark and their arguments" in {
      val b = Benchmark("name", List("testArg1", "testArg2"))(None)
      val id = benchmarkService.getIdOrInsert(b)

      def queryOpts = toolDb.query(s"select * from benchmarkArgument where benchmarkId = $id")

      benchmarkService.findById(id) mustEqual Some(b)
      queryOpts must have length 2

      benchmarkService.delete(id)

      benchmarkService.findById(id) mustBe empty
      queryOpts mustBe empty
    }

    "update and delete benchmark tasks" in {
      // insert a copy of task1
      val taskId = compilerBenchmarkTaskService.insert(task4)
      compilerBenchmarkTaskService.findById(taskId) mustEqual Some(task4)

      // update status
      val withRequestFailed = task4.copy(status = RequestFailed(StartBenchmark, "could not start benchmark"))(None)
      compilerBenchmarkTaskService.update(taskId, withRequestFailed)
      compilerBenchmarkTaskService.findById(taskId) mustEqual Some(withRequestFailed)

      // update status to a new one which doesn't have fields
      val withWait = withRequestFailed.copy(status = WaitForBenchmark)(None)
      compilerBenchmarkTaskService.update(taskId, withWait)
      compilerBenchmarkTaskService.findById(taskId) mustEqual Some(withWait)
      // removes fields of the `RequestFailed` status
      toolDb
        .query(
          s"select * from requestFailedFields where compilerBenchmarkTaskId = $taskId")
        .mustBe(empty)

      // delete task
      compilerBenchmarkTaskService.delete(taskId)
      compilerBenchmarkTaskService.findById(taskId) mustEqual None
      // also deletes entries in the compilerBenchmarkTaskBenchmark table
      toolDb
        .query(
          s"select * from compilerBenchmarkTaskBenchmark where compilerBenchmarkTaskId = $taskId")
        .mustBe(empty)
    }
  }
}
