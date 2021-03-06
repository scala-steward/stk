package replicate.messaging

import java.util.UUID

import org.slf4j.Logger
import replicate.alerts.Alerts
import replicate.messaging
import replicate.messaging.Message.{Severity, TextMessage}
import replicate.utils.Types.PhoneNumber
import replicate.utils.{FormatUtils, Glyphs}
import scalaz.@@

package object sms {

  private[sms] def amountToStatus(amount: Double): (Status, Double) = {
    import replicate.utils.Global.TextMessages.TopUp._
    if (amount < criticalAmount)
      (Critical, criticalAmount)
    else if (amount < warningAmount)
      (Warning, warningAmount)
    else if (amount < noticeAmount)
      (Notice, noticeAmount)
    else
      (Ok, 0)
  }

  private[sms] trait BalanceTracker {

    val log: Logger
    val messageTitle: String

    private[this] var currentStatus: Status = null
    private[this] var latestAlert: Option[UUID] = None

    private val icon: Some[String] = Some(Glyphs.telephoneReceiver)

    def trackBalance(balance: Double) = {
      val (status, limit) = amountToStatus(balance)
      log.debug("Balance for {} is {}", messageTitle, FormatUtils.formatEuros(balance))
      if (status != currentStatus) {
        val current = FormatUtils.formatEuros(balance)
        val message = (currentStatus, status) match {
          case (null, Ok) => s"Current balance is $current"
          case (_, Ok)    => s"Balance has been restored to $current"
          case _          => s"Balance is $current, below the limit of ${FormatUtils.formatEuros(limit)}"
        }
        currentStatus = status
        latestAlert.foreach(Alerts.cancelAlert)
        latestAlert = Some(Alerts.sendAlert(Message(TextMessage, severities(status), messageTitle, message, icon = icon)))
      }
    }

    def balanceError(failure: Throwable) = {
      log.error("Unable to get balance for {}", messageTitle, failure)
      latestAlert.foreach(Alerts.cancelAlert)
      latestAlert = Some(Alerts.sendAlert(messaging.Message(TextMessage, Severity.Critical, messageTitle, "Unable to get balance", icon = icon)))
    }

  }

  private[sms] trait SMSProtocol
  case class SMSMessage(recipient: String @@ PhoneNumber, text: String) extends SMSProtocol
  case class Balance(balance: Double) extends SMSProtocol
  case class BalanceError(failure: Throwable) extends SMSProtocol

}
