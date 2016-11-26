package akka.contrib.persistence.mongodb

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ExtendedActorSystem, Props}
import akka.persistence.query._
import akka.persistence.query.javadsl.{AllPersistenceIdsQuery => JAPIQ, CurrentEventsByPersistenceIdQuery => JCEBP, CurrentPersistenceIdsQuery => JCP, EventsByPersistenceIdQuery => JEBP}
import akka.persistence.query.scaladsl.{AllPersistenceIdsQuery, CurrentEventsByPersistenceIdQuery, CurrentPersistenceIdsQuery, EventsByPersistenceIdQuery}
import akka.stream.{FlowShape, Inlet, Outlet, OverflowStrategy, Attributes}
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.stream.javadsl.{Source => JSource}
import akka.stream.scaladsl._
import akka.stream.stage._
import com.typesafe.config.Config

import scala.collection.mutable

object MongoReadJournal {
  val Identifier = "akka-contrib-mongodb-persistence-readjournal"
}

class MongoReadJournal(system: ExtendedActorSystem, config: Config) extends ReadJournalProvider {

  private[this] val impl = MongoPersistenceExtension(system)(config).readJournal

  override def scaladslReadJournal(): scaladsl.ReadJournal = new ScalaDslMongoReadJournal(impl)

  override def javadslReadJournal(): javadsl.ReadJournal = new JavaDslMongoReadJournal(new ScalaDslMongoReadJournal(impl))
}

object ScalaDslMongoReadJournal {

  val eventToEventEnvelope: Flow[Event, EventEnvelope, NotUsed] = {
    // TODO Use zipWithIndex in akka 2.4.14
    Flow[Event].zip(Source.unfold(0L)(s => Some((s + 1, s)))).map { case (event, offset) => event.toEnvelope(offset) }
  }

  implicit class RichFlow[Mat](source: Source[Event, Mat]) {

    def toEventEnvelopes: Source[EventEnvelope, Mat] =
      source.via(eventToEventEnvelope)
  }
}

class ScalaDslMongoReadJournal(impl: MongoPersistenceReadJournallingApi)
  extends scaladsl.ReadJournal
    with CurrentPersistenceIdsQuery
    with CurrentEventsByPersistenceIdQuery
    with AllPersistenceIdsQuery
    with EventsByPersistenceIdQuery {

  import ScalaDslMongoReadJournal._

  def currentAllEvents(): Source[EventEnvelope, NotUsed] =
    Source.actorPublisher[Event](impl.currentAllEvents)
      .toEventEnvelopes
      .mapMaterializedValue(_ => NotUsed)

  override def currentPersistenceIds(): Source[String, NotUsed] =
    Source.actorPublisher[String](impl.currentPersistenceIds)
      .mapMaterializedValue(_ => NotUsed)

  override def currentEventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    require(persistenceId != null, "PersistenceId must not be null")
    Source.actorPublisher[Event](impl.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr))
      .toEventEnvelopes
      .mapMaterializedValue(_ => NotUsed)
  }

  def allEvents(): Source[EventEnvelope, NotUsed] = {

    val pastSource = Source.actorPublisher[Event](impl.currentAllEvents).mapMaterializedValue(_ => ())
    val realtimeSource = Source.actorRef[Event](100, OverflowStrategy.dropHead)
      .mapMaterializedValue(actor => impl.subscribeJournalEvents(actor))
    val removeDuplicatedEventsByPersistenceId = Flow[Event].via(new RemoveDuplicatedEventsByPersistenceId)
    (pastSource ++ realtimeSource).mapMaterializedValue(_ => NotUsed).via(removeDuplicatedEventsByPersistenceId).toEventEnvelopes
  }

  override def eventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): Source[EventEnvelope, NotUsed] = {
    require(persistenceId != null, "PersistenceId must not be null")
    val pastSource = Source.actorPublisher[Event](impl.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr))
      .mapMaterializedValue(_ => NotUsed)
    val realtimeSource = Source.actorRef[Event](100, OverflowStrategy.dropHead)
      .mapMaterializedValue { actor => impl.subscribeJournalEvents(actor); NotUsed }
    val stages = Flow[Event]
      .filter(_.pid == persistenceId)
      .filter(_.sn >= fromSequenceNr)
      .via(new StopAtSeq(toSequenceNr))
      .via(new RemoveDuplicatedEventsByPersistenceId)

    (pastSource concat realtimeSource).via(stages).toEventEnvelopes
  }

  override def allPersistenceIds(): Source[String, NotUsed] = {

    val pastSource = Source.actorPublisher[String](impl.currentPersistenceIds)
    val realtimeSource = Source.actorRef[Event](100, OverflowStrategy.dropHead)
      .map(_.pid).mapMaterializedValue(actor => impl.subscribeJournalEvents(actor))
    val removeDuplicatedPersistenceIds = Flow[String].via(new RemoveDuplicates)

    (pastSource ++ realtimeSource).mapMaterializedValue(_ => NotUsed).via(removeDuplicatedPersistenceIds)
  }
}

class JavaDslMongoReadJournal(rj: ScalaDslMongoReadJournal) extends javadsl.ReadJournal with JCP with JCEBP with JEBP with JAPIQ {
  def currentAllEvents(): JSource[EventEnvelope, NotUsed] = rj.currentAllEvents().asJava

  def allEvents(): JSource[EventEnvelope, NotUsed] = rj.allEvents().asJava

  override def currentPersistenceIds(): JSource[String, NotUsed] = rj.currentPersistenceIds().asJava

  override def currentEventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long): JSource[EventEnvelope, NotUsed] = {
    require(persistenceId != null, "PersistenceId must not be null")
    rj.currentEventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).asJava
  }

  override def eventsByPersistenceId(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long) = {
    require(persistenceId != null, "PersistenceId must not be null")
    rj.eventsByPersistenceId(persistenceId, fromSequenceNr, toSequenceNr).asJava
  }

  override def allPersistenceIds(): JSource[String, NotUsed] = rj.allPersistenceIds().asJava
}


trait JournalStream[Cursor] {
  def cursor(): Cursor

  def publishEvents(): Unit
}

class StopAtSeq(to: Long) extends GraphStage[FlowShape[Event, Event]] {
  val in = Inlet[Event]("flowIn")
  val out = Outlet[Event]("flowOut")

  override def shape: FlowShape[Event, Event] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        val ev = grab(in)
        push(out, ev)
        if (ev.sn == to) completeStage()
      }
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        pull(in)
      }
    })
  }
}

class RemoveDuplicatedEventsByPersistenceId extends GraphStage[FlowShape[Event, Event]] {

  private val in: Inlet[Event] = Inlet("in")
  private val out: Outlet[Event] = Outlet("out")

  override val shape: FlowShape[Event, Event] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {

    private val lastSequenceNrByPersistenceId = mutable.HashMap.empty[String, Long]

    override def onPush(): Unit = {
      val event = grab(in)
      lastSequenceNrByPersistenceId.get(event.pid) match {
        case Some(sn) if event.sn > sn =>
          push(out, event)
          lastSequenceNrByPersistenceId.update(event.pid, event.sn)
        case None =>
          push(out, event)
          lastSequenceNrByPersistenceId.update(event.pid, event.sn)
        case _ =>
          pull(in)
      }
    }
    override def onPull(): Unit = pull(in)

    setHandlers(in, out, this)
  }

}

class RemoveDuplicates[T] extends GraphStage[FlowShape[T, T]] {

  private val in: Inlet[T] = Inlet("in")
  private val out: Outlet[T] = Outlet("out")

  override val shape: FlowShape[T, T] = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) with InHandler with OutHandler {

    private val processed = mutable.HashSet.empty[T]

    override def onPush(): Unit = {
      val element = grab(in)
      if(processed(element)) {
        pull(in)
      } else {
        processed.add(element)
        push(out, element)
      }
    }

    override def onPull(): Unit = pull(in)

    setHandlers(in, out, this)
  }

}

trait MongoPersistenceReadJournallingApi {
  def currentAllEvents: Props

  def currentPersistenceIds: Props

  def currentEventsByPersistenceId(persistenceId: String, fromSeq: Long, toSeq: Long): Props

  def subscribeJournalEvents(subscriber: ActorRef): Unit
}

trait SyncActorPublisher[A, Cursor] extends ActorPublisher[A] with ActorLogging {

  import ActorPublisherMessage._

  override def preStart() = {
    context.become(streaming(initialCursor, 0))
    super.preStart()
  }

  protected def driver: MongoPersistenceDriver

  protected def initialCursor: Cursor

  protected def next(c: Cursor, atMost: Long): (Vector[A], Cursor)

  protected def isCompleted(c: Cursor): Boolean

  protected def discard(c: Cursor): Unit

  def receive = Actor.emptyBehavior

  def streaming(cursor: Cursor, offset: Long): Receive = {
    case _: Cancel | SubscriptionTimeoutExceeded =>
      discard(cursor)
      context.stop(self)
    case Request(_) =>
      val (filled, remaining) = next(cursor, totalDemand)
      filled foreach onNext
      if (isCompleted(remaining)) {
        onCompleteThenStop()
        discard(remaining)
      }
      else
        context.become(streaming(remaining, offset + filled.size))
  }
}
