package replicate.messaging

import akka.http.scaladsl.model.Uri
import Message._
import replicate.messaging.Message.Severity.Severity

case class Message(category: Category, severity: Severity, title: String, body: String, url: Option[Uri]) {
  override lazy val toString = s"[$title] $body${url.fold("")(l => s" ($l)")}"
}

object Message {

  sealed trait Category {
    override val toString = {
      val name = getClass.getSimpleName.dropRight(1)
      (name.head +: "[A-Z]".r.replaceAllIn(name.tail, m => s"_${m.group(0)}")).mkString.toLowerCase
    }
  }

  case object Administrativia extends Category
  case object Connectivity extends Category
  case object RaceInfo extends Category

  val allCategories = Array(Administrativia, Connectivity, RaceInfo)

  object Severity extends Enumeration {
    type Severity = Value
    val Debug, Verbose, Info, Warning, Error, Critical = Value
  }

}
