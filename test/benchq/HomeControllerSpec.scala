package benchq

import controllers.HomeController
import org.scalatestplus.play._
import play.api._
import play.api.test.Helpers._
import play.api.test._

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 *
 * For more information, see https://www.playframework.com/documentation/latest/ScalaTestingWithScalaTest
 */
class HomeControllerSpec extends PlaySpec {
  lazy val components = {
    val env = Environment.simple()
    val context = ApplicationLoader.createContext(env)
    new BenchQComponents(context)
  }
  implicit lazy val app = components.application

  "HomeController GET" should {
    "redirect on index" in {
      val controller = components.homeController
      val home = controller.index().apply(FakeRequest())
      status(home) mustBe SEE_OTHER
      redirectLocation(home) mustBe Some("/queue")
    }

    "render the queue" in {
      val controller = components.homeController
      val home = controller.queue().apply(FakeRequest())
      status(home) mustBe OK
      contentType(home) mustBe Some("text/html")
      contentAsString(home) must include("Benchmark Queue")
    }
  }
}
