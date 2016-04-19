package replicate.scrutineer

import akka.NotUsed
import akka.event.LoggingAdapter
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{ActorAttributes, Attributes, Materializer, Supervision}
import net.rfc1149.canape.Database
import replicate.models.CheckpointData
import replicate.scrutineer.Analyzer.ContestantAnalysis
import replicate.state.CheckpointsState

import scala.concurrent.Future

object CheckpointScrutineer {

  /**
   * Return a source of live analyzed race data, starting with historical race data.
   *
   * @param database the database to connect to
   * @param log the log to use to indicate problems
   * @param fm a materializer
   * @return a source of contestant analysis data
   */
  def checkpointScrutineer(database: Database)(implicit log: LoggingAdapter, fm: Materializer): Source[ContestantAnalysis, NotUsed] = {
    import fm.executionContext

    val source = Source.fromFuture(database.viewWithUpdateSeq[Int, CheckpointData]("replicate", "checkpoint", Seq("include_docs" → "true")))
      .flatMapConcat {
        case (lastSeq, checkpoints) ⇒
          val groupedByContestants = Source(checkpoints.filterNot(_._2.raceId == 0).groupBy(_._1).map(_._2.map(_._2)))
          val enterAndKeepLatest = groupedByContestants.mapAsync(1)(cps ⇒ Future.sequence(cps.dropRight(1).map(CheckpointsState.setTimes)).map(_ ⇒ cps.last))
          val changes =
            database.changesSource(Map("filter" → "_view", "view" → "replicate/checkpoint", "include_docs" → "true"), sinceSeq = lastSeq)
              .map(js ⇒ (js \ "doc").as[CheckpointData])
              .mapConcat {
                case cpd if cpd.raceId == 0 ⇒
                  log.warning("skipping checkpoint data of contestant {} at site {} because no race is defined", cpd.contestantId, cpd.siteId)
                  Nil
                case cpd ⇒
                  List(cpd)
              }
          enterAndKeepLatest ++ changes
      }

    val checkpointDataToPoints = Flow[CheckpointData].mapAsync(1)(checkpointData ⇒ CheckpointsState.setTimes(checkpointData))
      .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider) and Attributes.name("checkpointDataToPoints"))

    val pointsToAnalyzed = Flow[Seq[CheckpointData]].mapConcat { data ⇒
      try {
        List(Analyzer.analyze(data))
      } catch {
        case t: Throwable ⇒
          log.error(t, "unable to analyse contestant {} in race {}", data.head.contestantId, data.head.raceId)
          Nil
      }
    }.named("analyzer")

    // Analyze checkpoints as they arrive (after the initial batch),
    source.via(checkpointDataToPoints).via(pointsToAnalyzed)
  }

}