package pt.tecnico.dsi.akkastrator

import scala.concurrent.duration.Duration

import akka.testkit.TestProbe
import pt.tecnico.dsi.akkastrator.ActorSysSpec._
import pt.tecnico.dsi.akkastrator.DSL._
import pt.tecnico.dsi.akkastrator.Step7_TaskQuorumSpec._
import pt.tecnico.dsi.akkastrator.Task._
import shapeless.{::, HNil}

object Step7_TaskQuorumSpec {
  class TasksWithSameDestinationQuorum(destinations: Array[TestProbe]) extends ControllableOrchestrator(destinations) {
    // A - error: tasks with same destination
    FullTask("A") createTaskWith { case HNil =>
      new TaskQuorum(_)(minimumVotes = Majority, o => Seq(
        simpleMessageFulltask("0", 0, "0")(o),
        simpleMessageFulltask("1", 0, "1")(o)
      ))
    }
  }
  class TasksWithDifferentMessagesQuorum(destinations: Array[TestProbe]) extends ControllableOrchestrator(destinations) {
    case class AnotherMessage(s: String, id: Long)
    
    FullTask("A") createTaskWith { case HNil =>
      new TaskQuorum(_)(minimumVotes = Majority, o => Seq(
        simpleMessageFulltask("0", 0, "0")(o),
        fulltask("1", 1, AnotherMessage("1", _), "1")(o)
      ))
    }
  }
  
  // The length of each string is important. Do not change them. See Orchestrators below.
  val startingFruits = Seq("Farfalhi", "Kunami", "Funini", "Katuki", "Maraca")
  
  // 5*A the destinations of all inner tasks send an answer
  class SingleTaskQuorum(destinations: Array[TestProbe]) extends ControllableOrchestrator(destinations) {
    // This orchestrator serves us as a sort of incremental test, ramping up to a "complex" orchestrator.
    // If this test fails then there is a problem that is inherent to task quorum and not to some sort of
    // interplay between some other akkastrator abstraction.
    
    FullTask("A") createTask { _ =>
      new TaskQuorum(_)(minimumVotes = AtLeast(2), o =>
        startingFruits.zipWithIndex.map { case (fruit, i) =>
          fulltask(s"A-$fruit", i, SimpleMessage.apply, fruit.length)(o)
        }
      )
    }
  }
  
  // 5*A the destinations of two random inner tasks won't send an answer
  class SingleTaskQuorumWithoutSomeAnswers(destinations: Array[TestProbe]) extends SingleTaskQuorum(destinations) {
    // We created a new class just to ensure the persistenceId is different
  }
  
  // 5*A QuorumNotAchieved
  class QuorumNotAchieved(destinations: Array[TestProbe]) extends ControllableOrchestrator(destinations) {
    FullTask("A") createTask { _ =>
      new TaskQuorum(_)(minimumVotes = Majority, o =>
        // Every inner task will give a different answer
        Seq.tabulate(5)("a" * _).zipWithIndex.map { case (string, i) =>
          fulltask(s"A-$string", i, SimpleMessage.apply, string.length)(o)
        }
      )
    }
  }
  
  // A -> 5*B
  class TaskQuorumDependency(destinations: Array[TestProbe]) extends ControllableOrchestrator(destinations) {
    val a = simpleMessageFulltask("A", 0, startingFruits)
    FullTask("B", a) createTaskWith { case fruits :: HNil =>
      new TaskQuorum(_)(minimumVotes = Majority, o =>
        fruits.zipWithIndex.map { case (fruit, i) =>
          fulltask(s"B-$fruit", i + 1, SimpleMessage.apply, fruit.length)(o)
        }
      )
    }
  }
  
  // A -> 5*B (the first 2 tasks abort)
  class TaskQuorumDependencyWithAborts(destinations: Array[TestProbe]) extends ControllableOrchestrator(destinations) {
    val a = simpleMessageFulltask("A", 0, startingFruits)
    FullTask("B", a) createTaskWith { case fruits :: HNil =>
      new TaskQuorum(_)(minimumVotes = Majority, o =>
        fruits.zipWithIndex.map { case (fruit, i) =>
          fulltask(s"B-$fruit", i + 1, SimpleMessage.apply, fruit.length, abortOnReceive = i < 2)(o)
        }
      )
    }
  }
  
  //     5*B
  // A →⟨   ⟩→ 2*D
  //     5*C
  class ComplexTaskQuorum(destinations: Array[TestProbe]) extends ControllableOrchestrator(destinations) {
    val a = simpleMessageFulltask("A", 0, startingFruits)
    val b = FullTask("B", a, Duration.Inf) createTaskWith { case fruits :: HNil =>
      new TaskQuorum(_)(minimumVotes = Majority, o =>
        fruits.zipWithIndex.map { case (fruit, i) =>
          fulltask(s"B-$fruit", i + 6, SimpleMessage.apply, fruit.length)(o)
        }
      )
    }
    val c = FullTask("C", a, Duration.Inf) createTaskWith { case fruits :: HNil =>
      new TaskQuorum(_)(AtLeast(2), o =>
        fruits.zipWithIndex.map { case (fruit, i) =>
          fulltask(s"C-$fruit", i + 1, SimpleMessage.apply, fruit.length)(o)
        }
      )
    }
    // Using tuple syntax makes it prettier
    FullTask("D", (b, c), Duration.Inf) createTask { case (fruitsLengthB, fruitsLengthC) =>
      new TaskQuorum(_)(All, o => Seq(
        fulltask("D-B", 11, SimpleMessage.apply, fruitsLengthB)(o),
        fulltask("D-C", 12, SimpleMessage.apply, fruitsLengthC)(o)
      ))
    }
  }
  
  // 3*B (one of the task aborts)
  class SurpassingTolerance(abortingTaskId: Long, destinations: Array[TestProbe]) extends ControllableOrchestrator(destinations) {
    FullTask("A") createTaskWith { case HNil =>
      // Since minimumVotes = All the tolerance is 0.
      // Given that one of the tasks aborts so should the Quorum
      new TaskQuorum(_)(minimumVotes = All, o =>
        (0 to 2) map { i =>
          fulltask(s"B-$i", i, SimpleMessage.apply, "result", abortOnReceive = i == abortingTaskId)(o)
        }
      )
    }
  }
  
  // 3*A First task surpasses tolerance
  class FirstTaskSurpassesTolerance(destinations: Array[TestProbe]) extends SurpassingTolerance(0, destinations)
  // 3*A Middle task surpasses tolerance
  class MiddleTaskSurpassesTolerance(destinations: Array[TestProbe]) extends SurpassingTolerance(1, destinations)
  // 3*A Last task surpasses tolerance
  class LastTaskSurpassesTolerance(destinations: Array[TestProbe]) extends SurpassingTolerance(2, destinations)
  
  // 5*A QuorumNotAchieved in the last aborting task
  class QuorumNotAchievedInLastAbortingTask(destinations: Array[TestProbe]) extends ControllableOrchestrator(destinations) {
    FullTask("A") createTask { _ =>
      new TaskQuorum(_)(minimumVotes = Majority, o =>
        startingFruits.zipWithIndex.map { case (fruit, i) =>
          fulltask(s"A-$fruit", i, SimpleMessage.apply, fruit.length, abortOnReceive = i > 2)(o)
        }
      )
    }
  }
}
class Step7_TaskQuorumSpec extends ActorSysSpec {
  "An orchestrator with task quorum" should {
    "fail" when {
      "the tasksCreator generates tasks with the same destination" in {
        val testCase = new TestCase[TasksWithSameDestinationQuorum](1, Set("A")) {
          val transformations = withStartAndFinishTransformations(
            { secondState =>
              parentProbe expectMsg OrchestratorAborted
              
              secondState.updatedStatuses(
                "A" -> Aborted(new IllegalArgumentException("TasksCreator must generate tasks with distinct destinations."))
              )
            }
          )
        }
        testCase.testExpectedStatusWithRecovery()
      }
      "the tasksCreator generates tasks with different messages" in {
        val testCase = new TestCase[TasksWithDifferentMessagesQuorum](2, Set("A")) {
          val transformations = withStartAndFinishTransformations(
            { secondState =>
              parentProbe expectMsg OrchestratorAborted
          
              secondState.updatedStatuses(
                "A" -> Aborted(new IllegalArgumentException("TasksCreator must generate tasks with the same message."))
              )
            }
          )
        }
        testCase.testExpectedStatusWithRecovery()
      }
    }
    "execute the necessary tasks of the inner orchestrator" when {
      // 5*A
      "there's a single quorum" in {
        val testCase = new TestCase[SingleTaskQuorum](numberOfDestinations = 5, Set("A")) {
          val transformations = withStartAndFinishTransformations(
            { secondState =>
              startingFruits.indices.par.foreach { i =>
                pingPong(destinations(i))
              }
          
              secondState.updatedStatuses(
                "A" -> Waiting or Finished(6)
              )
            }, { thirdState =>
              // By this time some of the inner tasks of A might have already finished (we don't know which, if any).
              // The ones that have finished will not send a message to their destination,
              // however the ones that are still waiting will.
              //  If we don't pingPong for the waiting ones the test will fail since the inner orchestrator won't terminate.
              //  If we pingPong for the finished ones the expectMsg will timeout and throw an exception causing the test to erroneously fail.
              // To get out of this pickle we pingPong every destination but ignore any timeout error.
              startingFruits.indices.par.foreach { i =>
                pingPong(destinations(i), ignoreTimeoutError = true)
              }
          
              expectInnerOrchestratorTermination("A")
          
              thirdState.updatedStatuses(
                "A" -> Finished(6)
              )
            }
          )
        }
        testCase.testExpectedStatusWithRecovery()
      }
      
      // 5*A two random destinations won't send an answer
      "there's a single quorum two random destinations won't send an answer" in {
        val testCase = new TestCase[SingleTaskQuorumWithoutSomeAnswers](numberOfDestinations = 5, Set("A")) {
          val transformations = withStartAndFinishTransformations(
            { secondState =>
              // Each task is just computing fruit.length, which will result in List(8, 6, 6, 6, 6)
              // The minimumVotes is AtLeast(2), which means we need at least 2 equal responses.
              // So if 2 tasks don't answer we will still be able to achieve a quorum.
              import scala.util.Random
              Random.shuffle(0 to 4).drop(2).par.foreach { i =>
                pingPong(destinations(i))
              }
          
              secondState.updatedStatuses(
                "A" -> Waiting or Finished(6)
              )
            }, { thirdState =>
              // See the first test in this suite to understand why the timeout error is being ignored
              startingFruits.indices.par.foreach { i =>
                pingPong(destinations(i), ignoreTimeoutError = true)
              }
          
              expectInnerOrchestratorTermination("A")
          
              thirdState.updatedStatuses(
                "A" -> Finished(6)
              )
            }
          )
        }
        testCase.testExpectedStatusWithRecovery()
      }
  
      // 5*A QuorumNotAchieved
      "there's a single quorum that is impossible to be achieved (all inner task will get different answers)" in {
        val testCase = new TestCase[QuorumNotAchieved](numberOfDestinations = 5, Set("A")) {
          val transformations = withStartAndFinishTransformations(
            { secondState =>
              // The tasks will get as answers: 0, 1, 2, 3, 4, respectively.
              // The minimumVotes is Majority, which means we need at least 3 equal responses.
              // However every answer is different so a quorum will not be achieved.
              startingFruits.indices.par.foreach { i =>
                pingPong(destinations(i))
              }
          
              secondState.updatedStatuses(
                "A" -> Waiting or Aborted(QuorumNotAchieved)
              )
            }, { thirdState =>
              // See the first test in this suite to understand why the timeout error is being ignored
              startingFruits.indices.par.foreach { i =>
                pingPong(destinations(i), ignoreTimeoutError = true)
              }
          
              expectInnerOrchestratorTermination("A")
          
              thirdState.updatedStatuses(
                "A" -> Aborted(QuorumNotAchieved)
              )
            }
          )
        }
        testCase.testExpectedStatusWithRecovery()
      }
      
      // A -> 5*B
      "there's a single quorum as a dependency" in {
        val testCase = new TestCase[TaskQuorumDependency](numberOfDestinations = 6, Set("A")) {
          val transformations = withStartAndFinishTransformations(
            { secondState =>
              pingPong("A")
          
              secondState.updatedStatuses(
                "A" -> Finished(startingFruits),
                "B" -> Unstarted or Waiting
              )
            }, { thirdState =>
              handleResend("A")
          
              startingFruits.indices.par.foreach { i =>
                // See the first test in this suite to understand why the timeout error is being ignored
                pingPong(destinations(i + 1), ignoreTimeoutError = true)
              }
          
              expectInnerOrchestratorTermination("B")
          
              thirdState.updatedStatuses(
                "B" -> Finished(startingFruits.map(_.length))
              )
            }
          )
        }
        testCase.testExpectedStatusWithRecovery()
      }
      
      // A -> 5*B the first 2 tasks abort
      "there's a single quorum as a dependency, the first 2 tasks abort" in {
        val testCase = new TestCase[TaskQuorumDependencyWithAborts](numberOfDestinations = 6, Set("A")) {
          val transformations = withStartAndFinishTransformations(
            { secondState =>
              pingPong("A")
              
              secondState.updatedStatuses(
                "A" -> Finished(startingFruits),
                "B" -> Unstarted or Waiting
              )
            }, { thirdState =>
              handleResend("A")
    
              // Each task is just computing fruit.length, which will result in List(8, 6, 6, 6, 6)
              // The minimumVotes is Majority, which means we need at least 3 equal responses.
              // The first two tasks will abort which is in the threshold of the tolerance so the quorum will still be achieved.
              startingFruits.indices.par.foreach { i =>
                // See the first test in this suite to understand why the timeout error is being ignored
                pingPong(destinations(i + 1), ignoreTimeoutError = true)
              }
    
              expectInnerOrchestratorTermination("B")
    
              thirdState.updatedStatuses(
                "B" -> Finished(startingFruits.map(_.length))
              )
            }
          )
        }
        testCase.testExpectedStatusWithRecovery()
      }
      
      //     5*B
      // A →⟨   ⟩→ 2*D
      //     5*C
      "there are a complex web of quorums:" in {
        val testCase = new TestCase[ComplexTaskQuorum](numberOfDestinations = 13, Set("A")) {
          val transformations = withStartAndFinishTransformations(
            { secondState =>
              pingPong("A")
              
              secondState.updatedStatuses(
                "A" -> Finished(startingFruits),
                "B" -> Unstarted or Waiting,
                "C" -> Unstarted or Waiting
              )
            }, { thirdState =>
              handleResend("A")
    
              import scala.util.Random
              
              // For B tasks
              Random.shuffle(1 to 5).drop(1).foreach { i =>
                // See the first test in this suite to understand why the timeout error is being ignored
                pingPong(destinations(i + 5), ignoreTimeoutError = true)
              }
              // For C tasks
              Random.shuffle(1 to 5).drop(1).foreach { i =>
                // See the first test in this suite to understand why the timeout error is being ignored
                pingPong(destinations(i), ignoreTimeoutError = true)
              }
              
              expectInnerOrchestratorTermination("C")
              expectInnerOrchestratorTermination("B")
              
              thirdState.updatedStatuses(
                "B" -> Finished(6),
                "C" -> Finished(6),
                "D" -> Unstarted or Waiting
              )
            }, { fourthState =>
              // D Tasks+
              pingPong(destinations(11))
              pingPong(destinations(12))
              
              expectInnerOrchestratorTermination("D")
              
              fourthState.updatedStatuses(
                "D" -> Finished(6)
              )
            }
          )
        }
        testCase.testExpectedStatusWithRecovery()
      }
    }
    "handle the tolerance correctly" when {
      import scala.reflect.ClassTag
      def runTestWithTimeoutTask[T <: ControllableOrchestrator : ClassTag](timeoutTaskId: Int): Unit = {
        s"the task $timeoutTaskId surpasses tolerance" in {
          val testCase = new TestCase[T](numberOfDestinations = 3, Set("A")) {
            val transformations = withStartAndFinishTransformations(
              { secondState =>
                // The minimumVotes is All, which means we need at least 3 equal responses.
                // However one of the tasks will abort so the quorum will be impossible to achieve.
                (0 to 2).par.foreach { i =>
                  pingPong(destinations(i))
                }
          
                secondState.updatedStatuses(
                  "A" -> Waiting or Aborted(QuorumImpossibleToAchieve)
                )
              }, { thirdState =>
                // See the first test in this suite to understand why the timeout error is being ignored
                (0 to 2).par.foreach { i =>
                  pingPong(destinations(i), ignoreTimeoutError = true)
                }
          
                expectInnerOrchestratorTermination("A")
          
                thirdState.updatedStatuses(
                  "A" -> Aborted(QuorumImpossibleToAchieve)
                )
              }
            )
          }
          testCase.testExpectedStatusWithRecovery()
        }
      }
      runTestWithTimeoutTask[FirstTaskSurpassesTolerance](0)
      runTestWithTimeoutTask[MiddleTaskSurpassesTolerance](1)
      runTestWithTimeoutTask[LastTaskSurpassesTolerance](2)
      
      // 5*A QuorumNotAchieved in the last aborting task
      "the last task, which aborts, reaches the tolerance threshold" in {
        val testCase = new TestCase[QuorumNotAchievedInLastAbortingTask](numberOfDestinations = 5, Set("A")) {
          val transformations = withStartAndFinishTransformations(
            { secondState =>
              // Not in parallel on purpose
              startingFruits.indices.foreach { i =>
                pingPong(destinations(i))
              }
          
              secondState.updatedStatuses(
                "A" -> Waiting or Aborted(QuorumNotAchieved)
              )
            }, { thirdState =>
              // Not in parallel on purpose
              startingFruits.indices.foreach { i =>
                pingPong(destinations(i), ignoreTimeoutError = true)
              }
          
              expectInnerOrchestratorTermination("A")
          
              thirdState.updatedStatuses(
                "A" -> Aborted(QuorumNotAchieved)
              )
            }
          )
        }
        testCase.testExpectedStatusWithRecovery()
      }
    }
  }
}
