package replicate.stalking

import java.util.Calendar

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.pipe
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.Sink
import net.ceedubs.ficus.Ficus._
import net.rfc1149.canape.{Couch, Database}
import play.api.libs.json.JsObject
import replicate.alerts.RankingAlert
import replicate.messaging.PushbulletSMS
import replicate.utils.{ChangesActor, Global}

import scala.concurrent.Future

class Stalker(database: Database) extends Actor with ActorLogging {

  import Global.dispatcher

  private[this] implicit val fm = ActorFlowMaterializer()

  private[this] var smsActorRef: ActorRef = _

  /**
   * Stalker phone numbers by bib.
   */
  private[this] var stalkers: Map[Long, Seq[String]] = Map()

  /**
   * Stalkee name.
   */
  private[this] var name: Map[Long, String] = Map()

  /**
   * Stalkee race.
   */
  private[this] var race: Map[Long, Int] = Map()

  /**
   * Stalkee position, as site id, time, lap, and rank.
   */
  private[this] var position: Map[Long, (Int, Long, Int, Int)] = Map()

  private[this] var stalkStage: Long = 0

  private[this] def startPushbulletSMS(): Option[ActorRef] = {
    val config = Global.replicateConfig
    for (bearerToken <- config.as[Option[String]]("sms.bearer-token");
         userIden <- config.as[Option[String]]("sms.user-iden");
         deviceIden <- config.as[Option[String]]("sms.device-iden"))
      yield context.actorOf(Props(new PushbulletSMS(bearerToken, userIden, deviceIden)).withDispatcher("https-messaging-dispatcher"), "pushbullet-sms")
  }

  override def preStart(): Unit = {
    startPushbulletSMS() match {
      case Some(actorRef: ActorRef) =>
        log.debug("SMS service started")
        smsActorRef = actorRef
        launchInitialStalkersChanges()
      case None =>
        log.warning("no SMS service")
        context.stop(self)
    }
  }

  private[this] def updateStalkees(doc: JsObject): Boolean = {
    val bib = (doc \ "bib").as[Long]
    val newStalkers = (doc \ "stalkers").as[Seq[String]]
    if (newStalkers != stalkers.getOrElse(bib, Seq())) {
      if (newStalkers.nonEmpty) {
        stalkers += bib -> newStalkers
        name += bib -> s"${(doc \ "first_name").as[String]} ${(doc \ "name").as[String]} (dossard $bib)"
        race += bib -> (doc \ "race").as[Int]
      } else {
        stalkers -= bib
        name -= bib
        position -= bib
        race -= bib
      }
      true
    } else
      false
  }

  private[this] def contestantInfo(bib: Long): Future[(Int, Long, Int, Int)] = {
    val raceInfo = Global.infos.get.races(race(bib))
    for (ranking <- RankingAlert.raceRanking(raceInfo, database).flatMap(Couch.checkResponse[JsObject])
      .map(json => json \\ "value"))
      yield {
        val index = ranking.indexWhere(json => (json \ "bib").as[Long] == bib)
        val doc = ranking(index)
        val siteId = (doc \ "_id").as[String].split("-")(1).toInt
        val times = (doc \ "times").as[Array[Long]]
        val date = times.last
        val lap = times.length
        val rank = index + 1
        (siteId, date, lap, rank)
      }
  }

  private[this] def sendInfo(bib: Long, pos: (Int, Long, Int, Int)): Unit = {
    val recipients = stalkers.getOrElse(bib, Seq())
    if (recipients.nonEmpty) {
      log.info(s"Sending checkpoint information about ${name(bib)} to ${recipients.mkString(", ")}")
      pos match {
        case (siteId, timestamp, lap, rank) =>
          val infos = Global.infos.get
          val date = Calendar.getInstance()
          date.setTimeInMillis(timestamp)
          val time = "%d:%02d".format(date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE))
          val message = s"""${name(bib)} : dernier pointage au site "${infos.checkpoints(siteId).name}" à $time """ +
            s"(${infos.races(race(bib)).name}, tour $lap, ${infos.distances(siteId, lap)} kms, position $rank)"
          for (recipient <- recipients)
            PushbulletSMS.sendSMS(smsActorRef, recipient, message)
      }
    }
  }

  private[this] def initialStalkees(doc: JsObject): Long = {
    (doc \ "results").as[Array[JsObject]].foreach(json => updateStalkees((json \ "doc").as[JsObject]))
    (doc \ "last_seq").as[Long]
  }

  private[this] def launchInitialStalkersChanges(): Unit = {
    database.changesSource(Map("filter" -> "admin/stalked", "include_docs" -> "true"))
      .map(('initial, _))
      .runWith(Sink.actorRef(self, 'closedInitial))
  }

  private[this] def launchStalkersChanges(fromSeq: Long): ActorRef = {
    context.actorOf(Props(new ChangesActor(self, database, filter = Some("admin/stalked"), params = Map("include_docs" -> "true"),
      lastSeq = Some(0))), "stalkers-changes")
  }

  private[this] def launchCheckpointChanges(fromSeq: Long): Unit = {
    log.info(s"launching checkpoint change from $fromSeq with stage $stalkStage and stalkees $stalkers")
    val currentStage = stalkStage
    database.changesSource(Map("feed" -> "longpoll", "timeout" -> "60000", "filter" -> "admin/with-stalkers",
      "stalked" -> stalkers.keys.map(_.toString).mkString(","), "since" -> fromSeq.toString))
      .map(('checkpoint, _, currentStage))
      .runWith(Sink.actorRef(self, 'closedCheckpoint))
  }

  // After having looked at the initial state of the stalkers, we look for changes
  // in a continuous way. A stalk stage number gets incremented every time.
  // We also look using long-polling for changes in the checkpoints and match it
  // with the stalk occurrence number in order not to handle old events and take
  // the changes into account immediately.

  val receive: Receive = {

    case ('initial, doc: JsObject) =>
      val seq = initialStalkees(doc)
      launchStalkersChanges(seq)
      launchCheckpointChanges(seq)

    case 'closedInitial =>

    case ('checkpoint, doc: JsObject, stage: Long) =>
      if (stage == stalkStage) {
        log.info(s"Checkpoint info: $doc")
        val docs = (doc \ "results").as[Array[JsObject]]
        for (doc <- docs) {
          log.info(s"change in checkpoint for $doc")
          val bib = (doc \ "id").as[String].split("-")(2).toLong
          pipe(contestantInfo(bib).map(('ranking, bib, _))) to self
        }
        launchCheckpointChanges((doc \ "last_seq").as[Long])
      }

    case 'closedCheckpoint =>

    case ('ranking, bib: Long, pos: (Int, Long, Int, Int) @unchecked) =>
      if (stalkers.contains(bib)) {
        position.get(bib) match {
          case Some(oldPos) if oldPos == pos =>
          case _ =>
            position += bib -> pos
            sendInfo(bib, pos)
        }
      }

    case json: JsObject =>
      if (updateStalkees((json \ "doc").as[JsObject])) {
        stalkStage += 1
        launchCheckpointChanges((json \ "seq").as[Long])
      }

  }

}
