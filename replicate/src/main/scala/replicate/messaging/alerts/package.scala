package replicate.messaging

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._

package object alerts {

  def startFromConfig(context: ActorContext[_], officerId: String, config: Config): ActorRef[Messaging.Protocol] = {
    val service = config.as[String]("type")
    val behavior: Behavior[Messaging.Protocol] = Behaviors.setup { context ⇒
      service match {
        case "freemobile-sms" ⇒ new FreeMobileSMS(context, config.as[String]("user"), config.as[String]("password"))
        case "pushbullet"     ⇒ new Pushbullet(context, config.as[String]("token"))
        case "system"         ⇒ new SystemLogger(context)
        case "telegram"       ⇒ new Telegram(context, config.as[String]("id"))
        case s                ⇒ sys.error(s"Unknown officer type $s for officer $officerId")
      }
    }
    context.spawn(behavior, if (service == officerId) service else s"$service-$officerId")
  }

}
