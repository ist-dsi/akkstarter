package pt.tecnico.dsi.akkastrator

import scala.collection.immutable.HashMap

import akka.actor.{Actor, ActorLogging, ActorPath, PossiblyHarmful}
import akka.persistence._
import pt.tecnico.dsi.akkastrator.Task.Timeout

object Orchestrator {
  case class StartOrchestrator(id: Long)
  
  trait Success[R] extends Serializable {
    def id: Long
    def result: R
  }
  case class Finished[R](result: R, id: Long) extends Success[R]
  
  trait Failure extends Serializable {
    def id: Long
    def cause: Exception
  }
  case class Aborted(cause: Exception, id: Long) extends Failure
  case class TaskAborted[R](instigatorReport: Task.Report[R], cause: Exception, id: Long) extends Failure
    
  case object Status
  case class StatusResponse(tasks: IndexedSeq[Task.Report[_]])
  
  case object SaveSnapshot
  case object ShutdownOrchestrator extends PossiblyHarmful
  // This is used by the TaskSpawnOrchestrator
  case object TimeoutTasks extends PossiblyHarmful
}

/**
  * An Orchestrator executes a set of, possibly dependent, `Tasks`.
  * A task corresponds to sending a message to an actor, handling its response and possibly
  * mutate the internal state of the Orchestrator.
  *
  * The Orchestrator together with the Task is able to:
  *
  *  - Handling the persistence of the internal state maintained by both the Orchestrator and the Tasks.
  *  - Delivering messages with at-least-once delivery guarantee. The `DistinctIdsOrchestrator` ensures each destination
  *    will see an independent strictly monotonically increasing sequence number without gaps.
  *  - Handling Status messages, that is, if some actor is interested in querying the Orchestrator for its current
  *    status, the Orchestrator will respond with the status of each task.
  *  - Tasks that have no dependencies will be started right away and the Orchestrator will, from that point
  *    onwards, be prepared to handle the responses to the sent messages.
  *  - If the Orchestrator crashes, the state it maintains will be correctly restored.
  *
  * NOTE: the responses that are received must be Serializable.
  *
  * In order for the Orchestrator and the Tasks to be able to achieve all of this they have to access and modify
  * each others state directly. This means they are very tightly coupled with each other. To make this relation more
  * obvious and to enforce it, you will only be able to create tasks inside an orchestrator.
  *
  * If you have the need to refactor the creation of tasks so that you can use them in multiple orchestrators you can
  * leverage self type annotations like so:
  * {{{
  *   trait DatabaseTasks { self: AbstractOrchestrator[_] =>
  *     def createQueryTask(): Task = new Task("") {
  *       val destination: ActorPath = ???
  *       def createMessage(correlationId: CorrelationId): Any = ???
  *
  *       def behavior: Receive = ???
  *     }
  *   }
  * }}}
  *
  * @param settings
  * @tparam R the type of result this orchestrator returns when it finishes.
  */
sealed abstract class AbstractOrchestrator[R](val settings: Settings) extends PersistentActor with AtLeastOnceDelivery
  with ActorLogging with IdImplicits {
  import Orchestrator._
  
  /** The type of the state this orchestrator maintains. */
  type S <: State
  /** The type of Id this orchestrator handles. */
  type ID <: Id
  
  protected[akkastrator] case class StartTask(index: Int)
  protected[akkastrator] case class TaskTimedOut(index: Int, id: Long)
  
  /** This exists to make the creation of tasks easier. */
  final implicit val orchestrator: AbstractOrchestrator[_] = this
  
  /**
    * All the tasks this orchestrator will have will be stored here, which allows them to have a stable index.
    * Once every task is added to this list, this becomes "immutable", that is, it will no longer be modified.
    * This is a Vector because we want to ensure indexing elements and appending is very fast O(eC).
    */
  private[this] final var _tasks = Vector.empty[FullTask[_, _]]
  /**
    * INTERNAL API
    * Only FullTask takes advantage of the private[akkastrator].
    */
  private[akkastrator] def addTask(task: FullTask[_, _]): Int = {
    val index = tasks.length
    _tasks :+= task
    index
  }
  def tasks: Vector[FullTask[_, _]] = _tasks
  
  // By using HashMaps instead of HashSets we let the implementation of hashCode and equals of Task and Action to be free.
  // We use HashMaps to ensure remove/insert operations are very fast O(eC). The keys are the Task.index.
  // If you look at the Orchestrator as an ExecutionContext or as a process scheduler these lists are the queues.
  // INTERNAL API: These are only used by Task and Action.
  private[akkastrator] final var unstartedTasks = HashMap.empty[Int, FullTask[_, _]]
  private[akkastrator] final var waitingTasks = HashMap.empty[Int, Task[_]]
  private[akkastrator] final var finishedTasks = 0
  
  // The state this orchestrator is storing. Starts out empty.
  private[this] final var _state: S = _
  /** Gets the state this orchestrator currently has. */
  final def state: S = _state
  /** Sets the new state for this orchestrator. */
  final def state_=(state: S): Unit = _state = state
  
  // The id obtained in the StartOrchestrator message which prompted the execution of this orchestrator tasks
  // This is mainly used for TaskSpawnOrchestrator
  private[this] final var _startId: Long = _
  protected def startId: Long = _startId
  
  //Used to ensure inner orchestrators have different names and persistent ids
  private[this] final var _innerOrchestratorsLastId: Int = 0
  protected[akkastrator] final def nextInnerOrchestratorId(): Int = {
    val id = _innerOrchestratorsLastId
    _innerOrchestratorsLastId += 1
    id
  }
  
  /**
    * Roughly every X messages a snapshot will be saved. Set to 0 to disable automatic saving of snapshots.
    * By default this method returns the value defined in the configuration.
    *
    * This is just a rough value because the orchestrator will not save it in the snapshots.
    * In fact it will not save it at all. Instead the value of `lastSequenceNr` will be used to estimate
    * how many messages have been processed.
    *
    * You can trigger a save snapshot manually by sending a `SaveSnapshot` message to this orchestrator.
    * By setting this to 0 and sending `SaveSnapshot` messages you can manually choose when to save snapshots.
    */
  def saveSnapshotRoughlyEveryXMessages: Int = settings.saveSnapshotRoughlyEveryXMessages
  
  /**
    * Converts the deliveryId obtained from the deliver method of akka-persistence to ID.
    * Also updates this orchestrator state if necessary.
    */
  def deliveryId2ID(destination: ActorPath, deliveryId: DeliveryId): ID
  /**
    * Converts ID to the deliveryId needed for the confirmDelivery method of akka-persistence.
    */
  def ID2DeliveryId(destination: ActorPath, id: Long): DeliveryId
  /**
    * Ensures the received message was in fact destined to be received by `task`.
    */
  def matchId(task: Task[_], id: Long): Boolean
  
  /**
    * User overridable callback. Its called every time a task finishes.
    *
    * You can use this to implement very refined termination strategies.
    *
    * This method is not invoked when a task aborts.
    */
  def onTaskFinish(finishedTask: FullTask[_, _]): Unit = ()
  
  /**
    * User overridable callback. Its called once every task finishes.
    * By default logs that the Orchestrator has finished then stops it.
    *
    * You can use this to implement your termination strategy.
    *
    * If a orchestrator starts without tasks it will finish right away.
    */
  def onFinish(): Unit = {
    log.info(s"${self.path.name} Finished!")
    context stop self
  }
  
  /**
    * User overridable callback. Its called when a task instigates an abort.
    * By default logs that the Orchestrator has aborted then stops it.
    *
    * You can use this to implement your termination strategy.
    *
    * Note: if you invoke become/unbecome inside this method, the contract that states
    *       <cite>"Waiting tasks or tasks which do not have this task as a dependency will
    *       remain untouched"</cite> will no longer be guaranteed.
    *       If you wish to still have this guarantee you can do {{{
    *         context.become(computeCurrentBehavior() orElse yourBehavior)
    *       }}}
    *
    * @param instigator the task that instigated the abort.
    * @param reply the reply from the task destination that caused the abort.
    * @param tasks Map with the tasks states at the moment of abort.
    */
  def onAbort(instigator: FullTask[_, _], reply: Any, cause: Exception, tasks: Map[Task.State, Seq[FullTask[_, _]]]): Unit = {
    log.info(s"${self.path.name} Aborted due to $cause!")
    context.parent ! TaskAborted(instigator.report, cause, startId)
    context stop self
  }
  
  final def recoveryAwarePersist(event: Any)(handler: => Unit): Unit = {
    if (orchestrator.recoveryRunning) {
      // When recovering the event is already persisted no need to persist it again.
      handler
    } else {
      orchestrator.persist(event)(_ => handler)
    }
  }
  
  final def computeCurrentBehavior(): Receive = {
    val baseCommands: Actor.Receive = alwaysAvailableCommands orElse {
      case StartTask(index) => tasks(index).start()
      case TaskTimedOut(index, id) => waitingTasks.get(index).foreach(_.timeout(receivedMessage = None, id))
    }
    // baseCommands is the first receive to guarantee that ShutdownOrchestrator, SaveSnapshot, StartTask and Status
    // won't be taken first by one of the tasks behaviors or the extraCommands. Similarly extraCommands
    // is the last receive to ensure it doesn't take one of the messages of the waiting task behaviors.
    waitingTasks.values.map(_.behavior).fold(baseCommands)(_ orElse _) orElse extraCommands
  }
  
  private def start(): Unit = {
    if (tasks.isEmpty) {
      onFinish()
    } else if (!recoveryRunning) {
      // When a task is created it adds itself to:
      // · waitingTasks, if its dependencies == HNil
      // · unstartedTasks, otherwise.
      // So in order to start the orchestrator we only need to start the tasks in the waitingTasks map.
      waitingTasks.foreach { case (_, task) =>
        // The start will eventually invoke context become computeCurrentBehavior()
        task.start()
      }
      // If recovery is running we don't need to start the tasks because we will eventually handle a MessageSent
      // which will start the task(s).
    }
  }
  
  final def unstarted: Actor.Receive = {
    case m @ StartOrchestrator(id) =>
      recoveryAwarePersist(m) {
        _startId = id
        log.info(s"${self.path.name} - $m")
        start()
      }
  }
  final def alwaysAvailableCommands: Actor.Receive = {
    case Status =>
      sender() ! StatusResponse(tasks.map(_.report))
    case SaveSnapshot =>
      saveSnapshot(_state)
    case ShutdownOrchestrator =>
      context stop self
    case TimeoutTasks =>
      waitingTasks.values.foreach { task =>
        // task is waiting which ensures it has a expectedId aka .get will never throw
        task.timeout(receivedMessage = None, task.expectedId.get.self)
      }
  }
  
  final def receiveCommand: Actor.Receive = unstarted orElse alwaysAvailableCommands orElse extraCommands
  
  /**
    * Override this method to add extra commands that are always handled by this orchestrator (except when recovering).
    */
  def extraCommands: Actor.Receive = PartialFunction.empty[Any, Unit]
  
  def receiveRecover: Actor.Receive = unstarted orElse {
    case SnapshotOffer(_, offeredSnapshot: State) =>
      state = offeredSnapshot.asInstanceOf[S]
    case MessageSent(taskIndex) =>
      val task = tasks(taskIndex)
      log.info(task.withLogPrefix("Recovering MessageSent."))
      task.start()
    case MessageReceived(taskIndex, message) =>
      val task = waitingTasks(taskIndex)
      log.info(task.withLogPrefix("Recovering MessageReceived."))
      // We need to deal with the Timeout message explicitly because if behavior does not handle it invoking
      // behavior would throw a MatchError. Unfortunately this is an overhead we cannot overcome.
      message match {
        case Timeout(id) => task.timeout(receivedMessage = None, id)
        case _ => task.behavior(message)
      }
    case RecoveryCompleted =>
      if (tasks.nonEmpty) {
        val tasksString = tasks.map(t => t.withLogPrefix(t.state.toString)).mkString("\n\t")
        log.debug(s"""${self.path.name} - recovery completed:
                     |\t$tasksString
                     |\tNumber of unconfirmed messages: $numberOfUnconfirmed""".stripMargin)
      }
  }
}

/**
  * In a simple orchestrator the same sequence number (of akka-persistence) is used for all the
  * destinations of the orchestrator. Because of this, ID = DeliveryId, and matchId only checks the deliveryId
  * as that will be enough information to disambiguate which task should handle the response.
  */
abstract class Orchestrator[R](settings: Settings = new Settings()) extends AbstractOrchestrator[R](settings) {
  /** This orchestrator accepts any kind of State. */
  final type S = State
  state = EmptyState
  
  /** This orchestrator uses DeliveryId directly because the same sequence number (of the akka-persistence)
    * is used for all of its destinations. */
  final type ID = DeliveryId
  
  final def deliveryId2ID(destination: ActorPath, deliveryId: DeliveryId): DeliveryId = deliveryId
  final def ID2DeliveryId(destination: ActorPath, id: Long): DeliveryId = id
  final def matchId(task: Task[_], id: Long): Boolean = {
    val deliveryId: DeliveryId = id
    val matches = task.expectedDeliveryId.contains(deliveryId)
    
    log.debug(task.withLogPrefix{
      String.format(
        s"""matchId:
            |          │ DeliveryId
            |──────────┼─────────────────
            |    VALUE │ %s
            | EXPECTED │ %s
            | Matches: %s""".stripMargin,
        Some(deliveryId), task.expectedDeliveryId,
        matches.toString.toUpperCase
      )
    })
    matches
  }
}

/**
  * In a DistinctIdsOrchestrator an independent sequence is used for each destination of the orchestrator.
  * To be able to achieve this, an extra state is needed: the DistinctIds.
  * In this orchestrator the delivery id is not sufficient to disambiguate which task should handle the message,
  * so the ID = CorrelationId and the matchId also needs to check the sender.
  * There is also the added necessity of being able to compute the correlation id for a given (sender, deliveryId)
  * as well as translating a correlation id back to a delivery id.
  */
abstract class DistinctIdsOrchestrator[R](settings: Settings = new Settings()) extends AbstractOrchestrator[R](settings) {
  /** This orchestrator requires that the state includes DistinctIds. */
  final type S = State with DistinctIds
  state = MinimalState()
  
  /** This orchestrator uses CorrelationId for its ID.
    * This is needed to ensure every destination sees an independent sequence. */
  final type ID = CorrelationId
  
  final def deliveryId2ID(destination: ActorPath, deliveryId: DeliveryId): CorrelationId = {
    val correlationId = state.nextCorrelationIdFor(destination)
    
    state = state.updatedIdsPerDestination(destination, correlationId -> deliveryId)
    log.debug(s"State for $destination is now:\n\t" + state.idsOf(destination).mkString("\n\t"))
    
    correlationId
  }
  final def ID2DeliveryId(destination: ActorPath, id: Long): DeliveryId = {
    state.deliveryIdFor(destination, id)
  }
  final def matchId(task: Task[_], id: Long): Boolean = {
    val senderPath = sender().path
    val correlationId: CorrelationId = id
    val deliveryId = state.deliveryIdFor(task.destination, correlationId)
    
    val matches = (if (recoveryRunning) true else senderPath == task.destination) &&
      task.state == Task.Waiting && task.expectedDeliveryId.contains(deliveryId)
    
    log.debug(task.withLogPrefix{
      val senderPathString = senderPath.toStringWithoutAddress
      val destinationString = task.destination.toStringWithoutAddress
      val length = senderPathString.length max destinationString.length
      String.format(
        s"""MatchId:
           |($destinationString, $correlationId) resolved to $deliveryId
           |          │ %${length}s │ DeliveryId
           |──────────┼─%${length}s─┼──────────────────────────────
           |    VALUE │ %${length}s │ %s
           | EXPECTED │ %${length}s │ %s
           | Matches: %s""".stripMargin,
        "SenderPath",
        "─" * length,
        senderPathString, Some(deliveryId),
        destinationString, task.expectedDeliveryId,
        matches.toString.toUpperCase + (if (recoveryRunning) " because recovery is running." else "")
      )
    })
    matches
  }
}