package benchq

import controllers.HomeController
import org.scalatest.BeforeAndAfterAll
import org.scalatestplus.play._
import play.api._
import play.api.db.evolutions.Evolutions
import play.api.db.{Database, Databases}
import play.api.test.Helpers._
import play.api.test._

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 *
 * For more information, see https://www.playframework.com/documentation/latest/ScalaTestingWithScalaTest
 */
class HomeControllerSpec extends PlaySpec with BeforeAndAfterAll {
  lazy val components = {
    val env = Environment.simple()
    val context = ApplicationLoader.createContext(env)
    new BenchQComponents(context) {
      // prevent connecting to the real db
      override lazy val database: Database = Databases.inMemory()
    }
  }
  implicit lazy val app = components.application

  override def beforeAll(): Unit = {
    Evolutions.applyEvolutions(components.database)
  }

  override def afterAll(): Unit = {
    components.database.shutdown()
  }

  "HomeController GET" should {
    "redirect on index" in {
      val controller = components.homeController
      val home = controller.index().apply(FakeRequest())
      status(home) mustBe SEE_OTHER
      redirectLocation(home) mustBe Some(components.config.Http.reverseRoutePrefix + "/tasks")
    }

    "render the queue" in {
      val controller = components.homeController
      val home = controller.tasks().apply(FakeRequest())
      status(home) mustBe OK
      contentType(home) mustBe Some("text/html")
      contentAsString(home) must include("Benchmark Tasks")
    }
  }
}
