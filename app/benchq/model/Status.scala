package benchq
package model

sealed trait Status {
  def companion: StatusCompanion
  def name: String
}

sealed trait StatusCompanion {
  def companion: StatusCompanion = this
  def name: String = StatusCompanion.companionName(this)
}

object Status {
  def initial = CheckScalaVersionAvailable
  case object CheckScalaVersionAvailable extends Status with StatusCompanion
  case object WaitForScalaVersionAvailable extends Status with StatusCompanion
  case object StartScalaBuild extends Status with StatusCompanion
  case object WaitForScalaBuild extends Status with StatusCompanion
  case object StartBenchmark extends Status with StatusCompanion
  case object WaitForBenchmark extends Status with StatusCompanion
  case object SendResults extends Status with StatusCompanion
  case object WaitForSendResults extends Status with StatusCompanion
  case object Done extends Status with StatusCompanion
  case class RequestFailed(previousStatus: Status, message: String) extends Status {
    def companion: StatusCompanion = RequestFailed
    def name: String = companion.name
  }
  object RequestFailed extends StatusCompanion
}

object StatusCompanion {
  import Status._

  private val companionName: Map[StatusCompanion, String] = {
    def name(s: StatusCompanion) = s.getClass.getName.split('$').last
    List(
      CheckScalaVersionAvailable,
      WaitForScalaVersionAvailable,
      StartScalaBuild,
      WaitForScalaBuild,
      StartBenchmark,
      WaitForBenchmark,
      SendResults,
      WaitForSendResults,
      Done,
      RequestFailed
    ).map(s => (s, name(s))).toMap
  }

  private val nameToCompanion: Map[String, StatusCompanion] = companionName.map(_.swap)

  def allCompanions: Set[StatusCompanion] = companionName.keySet

  def actionableCompanions: Set[StatusCompanion] =
    Set(CheckScalaVersionAvailable, StartScalaBuild, StartBenchmark, SendResults)

  def companion(s: String): StatusCompanion = nameToCompanion(s)
}
