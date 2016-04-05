package pt.tecnico.dsi.akkastrator

import akka.actor.Actor._
import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorPath, ActorRef, OneForOneStrategy, Props}
import akka.event.LoggingReceive
import akka.testkit.{TestKit, TestProbe}
import pt.tecnico.dsi.akkastrator.Message.{Message, MessageId}

import scala.concurrent.duration.DurationInt

trait TestUtils { self: TestKit =>

  def echoCommand(name: String, _destination: ActorPath, message: MessageId => SimpleMessage,
                  dependencies: Set[Command] = Set.empty[Command])(implicit orchestrator: Orchestrator): Command = {
    import orchestrator.context //Needed for the LoggingReceive

    new Command(name, dependencies) {
      val destination: ActorPath = _destination
      def createMessage(id: MessageId): Message = message(id)

      def behavior: Receive = LoggingReceive {
        case m: SimpleMessage if matchSenderAndId(m) =>
          orchestrator.log.info(s"$loggingPrefix Received $m from ${orchestrator.sender()}")
          finish(m)
      }
    }
  }

  def fabricatedParent(childProps: Props, childName: String): (TestProbe, ActorRef) = {
    val parentProxy = TestProbe()
    val parent = system.actorOf(Props(new Actor {

      override def supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 1.minute) {
        case _: Throwable => Stop
      }

      val child = context.actorOf(childProps, childName)
      def receive = {
        case x if sender == child => parentProxy.ref forward x
        case x => child forward x
      }
    }))

    (parentProxy, parent)
  }
  
}
