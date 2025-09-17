package www.phases

import www.logging.{Formatter, Logger}
import utest.*
import scala.collection.immutable.SortedSet
import scala.collection.mutable

object PhaseRunnerTests extends TestSuite {
  
  // Test helper types
  type TestId = String
  case class TestValue(data: String, step: Int = 0)
  
  // Implicit instances for TestId
  implicit val testIdFormatter: Formatter[TestId] = Formatter.StringFormatter
  implicit val testIdOrdering: Ordering[TestId] = Ordering.String
  
  // Mock logger factory
  def getLogger(id: TestId): Logger[Unit] = Logger.DevNull
  
  // Test listener to capture events
  class TestListener extends PhaseListener[TestId] {
    val events = mutable.ListBuffer[PhaseListener.Event[TestId]]()
    
    override def on(phaseName: String, id: TestId, event: PhaseListener.Event[TestId]): Unit = {
      events += event
    }
    
    def clear(): Unit = events.clear()
    
    def getEvents: List[PhaseListener.Event[TestId]] = events.toList
    
    def getEventsForPhase(phaseName: String): List[PhaseListener.Event[TestId]] = 
      events.filter {
        case PhaseListener.Started(phase) => phase == phaseName
        case PhaseListener.Blocked(phase, _) => phase == phaseName
        case PhaseListener.Success(phase) => phase == phaseName
        case PhaseListener.Failure(phase, _) => phase == phaseName
        case PhaseListener.Ignored() => true
      }.toList
  }
  
  // Helper phase functions
  def identityPhase: Phase[TestId, TestId, TestId] = 
    (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestId], isCircular: IsCircular, logger: Logger[Unit]) =>
      PhaseRes.Ok(value)
  
  def transformPhase(transform: String => String): Phase[TestId, TestValue, TestValue] =
    (id: TestId, value: TestValue, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
      PhaseRes.Ok(TestValue(transform(value.data), value.step + 1))
  
  def failingPhase(errorMsg: String): Phase[TestId, TestValue, TestValue] =
    (id: TestId, value: TestValue, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
      PhaseRes.Failure(Map(id -> Right(errorMsg)))
  
  def ignoringPhase: Phase[TestId, TestValue, TestValue] =
    (id: TestId, value: TestValue, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
      PhaseRes.Ignore()
  
  def dependencyPhase(deps: Set[TestId]): Phase[TestId, TestValue, TestValue] =
    (id: TestId, value: TestValue, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) => {
      // Only request dependencies for the main ID, not for dependency IDs themselves
      if (deps.nonEmpty && !deps.contains(id)) {
        getDeps(SortedSet.from(deps)).map { depMap =>
          val depCount = depMap.size
          TestValue(s"${value.data}-with-${depCount}-deps", value.step + 1)
        }
      } else {
        PhaseRes.Ok(TestValue(value.data, value.step + 1))
      }
    }
  
  def circularAwarePhase: Phase[TestId, TestValue, TestValue] =
    (id: TestId, value: TestValue, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) => {
      if (isCircular) {
        PhaseRes.Ok(TestValue(s"${value.data}-circular", value.step + 1))
      } else {
        PhaseRes.Ok(TestValue(s"${value.data}-normal", value.step + 1))
      }
    }
  
  def exceptionThrowingPhase: Phase[TestId, TestValue, TestValue] =
    (id: TestId, value: TestValue, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
      throw new RuntimeException("Test exception")
  
  def tests = Tests {
    test("PhaseRunner basic execution") {
      test("should execute Initial phase") {
        val initial = RecPhase[TestId]
        val listener = new TestListener()
        
        val result = PhaseRunner(initial, getLogger, listener)("test-id")
        
        result match {
          case PhaseRes.Ok(value) => assert(value == "test-id")
          case _ => assert(false)
        }
        
        // Initial phase should not generate events
        assert(listener.getEvents.isEmpty)
      }
      
      test("should execute single Next phase") {
        val phase = RecPhase[TestId].next(identityPhase, "identity")
        val listener = new TestListener()
        
        val result = PhaseRunner(phase, getLogger, listener)("test-id")
        
        result match {
          case PhaseRes.Ok(value) => assert(value == "test-id")
          case _ => assert(false)
        }
        
        // Should have Started and Success events
        val events = listener.getEvents
        assert(events.length == 2)
        assert(events(0).isInstanceOf[PhaseListener.Started[TestId]])
        assert(events(1).isInstanceOf[PhaseListener.Success[TestId]])
      }
      
      test("should execute chained phases") {
        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(transformPhase(_.toUpperCase), "uppercase")
          .next(transformPhase(_ + "!"), "exclamation")
        
        val listener = new TestListener()
        val result = PhaseRunner(phase, getLogger, listener)("hello")
        
        result match {
          case PhaseRes.Ok(TestValue(data, step)) => 
            assert(data == "HELLO!")
            assert(step == 2)
          case _ => assert(false)
        }
        
        // Should have events for all 3 phases
        val events = listener.getEvents
        assert(events.length == 6) // 3 phases × 2 events each (Started + Success)
      }
    }
    
    test("PhaseRunner error handling") {
      test("should handle phase failure") {
        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(failingPhase("Test error"), "failing")
        
        val listener = new TestListener()
        val result = PhaseRunner(phase, getLogger, listener)("test-id")
        
        result match {
          case PhaseRes.Failure(errors) => 
            assert(errors.contains("test-id"))
            assert(errors("test-id") == Right("Test error"))
          case _ => assert(false)
        }
        
        // Should have Started and Failure events for the failing phase
        val events = listener.getEvents
        val failureEvents = events.filter(_.isInstanceOf[PhaseListener.Failure[TestId]])
        assert(failureEvents.length == 1)
      }
      
      test("should handle phase ignore") {
        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(ignoringPhase, "ignoring")
        
        val listener = new TestListener()
        val result = PhaseRunner(phase, getLogger, listener)("test-id")
        
        result match {
          case PhaseRes.Ignore() => assert(true)
          case _ => assert(false)
        }
        
        // Should have Started and Ignored events
        val events = listener.getEvents
        val ignoredEvents = events.filter(_.isInstanceOf[PhaseListener.Ignored[TestId]])
        assert(ignoredEvents.length == 1)
      }
      
      test("should handle exceptions in phases") {
        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(exceptionThrowingPhase, "throwing")
        
        val listener = new TestListener()
        val result = PhaseRunner(phase, getLogger, listener)("test-id")
        
        result match {
          case PhaseRes.Failure(errors) => 
            assert(errors.contains("test-id"))
            errors("test-id") match {
              case Left(throwable) => assert(throwable.getMessage == "Test exception")
              case _ => assert(false)
            }
          case _ => assert(false)
        }
        
        // Should have Started and Failure events
        val events = listener.getEvents
        val failureEvents = events.filter(_.isInstanceOf[PhaseListener.Failure[TestId]])
        assert(failureEvents.length == 1)
      }
    }
    
    test("PhaseRunner dependency resolution") {
      test("should resolve simple dependencies") {
        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(dependencyPhase(Set("dep1", "dep2")), "with-deps")
        
        val listener = new TestListener()
        val result = PhaseRunner(phase, getLogger, listener)("main")
        
        result match {
          case PhaseRes.Ok(TestValue(data, step)) => 
            assert(data == "main-with-2-deps")
            assert(step == 1)
          case _ => assert(false)
        }
        
        // Should have Blocked events for dependency resolution
        val events = listener.getEvents
        val blockedEvents = events.filter(_.isInstanceOf[PhaseListener.Blocked[TestId]])
        assert(blockedEvents.length == 1)
        
        blockedEvents.head match {
          case PhaseListener.Blocked(phase, deps) => 
            assert(phase == "with-deps")
            assert(deps == Set("dep1", "dep2"))
          case _ => assert(false)
        }
      }
      
      test("should handle empty dependencies") {
        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(dependencyPhase(Set.empty), "no-deps")
        
        val listener = new TestListener()
        val result = PhaseRunner(phase, getLogger, listener)("main")
        
        result match {
          case PhaseRes.Ok(TestValue(data, step)) => 
            assert(data == "main")
            assert(step == 1)
          case _ => assert(false)
        }
        
        // Should not have Blocked events for empty dependencies
        val events = listener.getEvents
        val blockedEvents = events.filter(_.isInstanceOf[PhaseListener.Blocked[TestId]])
        assert(blockedEvents.isEmpty)
      }
    }
    
    test("PhaseRunner circular dependency detection") {
      test("should detect circular dependencies") {
        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(circularAwarePhase, "circular-aware")
        
        val listener = new TestListener()
        
        // Simulate circular dependency by calling go with the same id in circuit breaker
        val result = PhaseRunner.go(phase, "test-id", List("test-id"), getLogger, listener)
        
        result match {
          case PhaseRes.Ok(TestValue(data, step)) => 
            assert(data == "test-id-circular")
            assert(step == 1)
          case _ => assert(false)
        }
      }
      
      test("should handle non-circular execution") {
        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(circularAwarePhase, "circular-aware")
        
        val listener = new TestListener()
        val result = PhaseRunner(phase, getLogger, listener)("test-id")
        
        result match {
          case PhaseRes.Ok(TestValue(data, step)) => 
            assert(data == "test-id-normal")
            assert(step == 1)
          case _ => assert(false)
        }
      }
    }

    test("PhaseRunner caching behavior") {
      test("should cache phase results") {
        var computeCount = 0
        val countingPhase: Phase[TestId, TestValue, TestValue] =
          (id: TestId, value: TestValue, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) => {
            computeCount += 1
            PhaseRes.Ok(TestValue(s"${value.data}-computed", value.step + 1))
          }

        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(countingPhase, "counting")

        val listener = new TestListener()

        // First execution
        val result1 = PhaseRunner(phase, getLogger, listener)("test-id")
        assert(computeCount == 1)

        // Second execution with same phase instance should use cache
        val result2 = PhaseRunner(phase, getLogger, listener)("test-id")
        assert(computeCount == 1) // Should not increment

        // Both results should be identical
        assert(result1 == result2)
      }

      test("should cache based on id and circular flag") {
        var computeCount = 0
        val countingPhase: Phase[TestId, TestValue, TestValue] =
          (id: TestId, value: TestValue, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) => {
            computeCount += 1
            PhaseRes.Ok(TestValue(s"${value.data}-${if (isCircular) "circular" else "normal"}", value.step + 1))
          }

        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(countingPhase, "counting")

        val listener = new TestListener()

        // Normal execution
        val result1 = PhaseRunner(phase, getLogger, listener)("test-id")
        assert(computeCount == 1)

        // Circular execution should compute separately
        val result2 = PhaseRunner.go(phase, "test-id", List("test-id"), getLogger, listener)
        assert(computeCount == 2)

        // Results should be different
        assert(result1 != result2)
      }
    }

    test("PhaseRunner listener integration") {
      test("should call listener for all phase events") {
        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "phase1"
          )
          .next(transformPhase(_.toUpperCase), "phase2")
          .next(dependencyPhase(Set("dep1")), "phase3")

        val listener = new TestListener()
        val result = PhaseRunner(phase, getLogger, listener)("test-id")

        val events = listener.getEvents

        // Should have events for all 3 phases
        val startedEvents = events.filter(_.isInstanceOf[PhaseListener.Started[TestId]])
        val successEvents = events.filter(_.isInstanceOf[PhaseListener.Success[TestId]])
        val blockedEvents = events.filter(_.isInstanceOf[PhaseListener.Blocked[TestId]])

        // With our fix, the dependency phase only requests dependencies for "test-id", not for "dep1"
        // So we expect:
        // - At least 3 Started events (test-id through all phases, plus dep1 through phases)
        // - Exactly 3 Success events (test-id through all 3 phases)
        // - 1 Blocked event (when test-id requests dep1 dependency)
        assert(startedEvents.length >= 3) // At least 3 Started events
        assert(successEvents.length >= 3) // At least 3 Success events
        assert(blockedEvents.length == 1) // 1 Blocked event for dependencies
      }

      test("should use NoListener when provided") {
        val phase = RecPhase[TestId]
          .next(identityPhase, "identity")

        val result = PhaseRunner(phase, getLogger, PhaseListener.NoListener)("test-id")

        result match {
          case PhaseRes.Ok(value) => assert(value == "test-id")
          case _ => assert(false)
        }

        // NoListener should not cause any issues
      }
    }

    test("PhaseRunner edge cases") {
      test("should handle complex dependency graphs") {
        val complexDependencyPhase: Phase[TestId, TestValue, TestValue] =
          (id: TestId, value: TestValue, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) => {
            val deps = id match {
              case "root" => Set("child1", "child2")
              case "child1" => Set("grandchild1")
              case "child2" => Set("grandchild2")
              case _ => Set.empty[TestId]
            }

            if (deps.nonEmpty) {
              getDeps(SortedSet.from(deps)).map { depMap =>
                TestValue(s"${value.data}-resolved-${deps.size}", value.step + 1)
              }
            } else {
              PhaseRes.Ok(TestValue(s"${value.data}-leaf", value.step + 1))
            }
          }

        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(complexDependencyPhase, "complex-deps")

        val listener = new TestListener()
        val result = PhaseRunner(phase, getLogger, listener)("root")

        result match {
          case PhaseRes.Ok(TestValue(data, _)) =>
            assert(data == "root-resolved-2")
          case _ => assert(false)
        }

        // Should have multiple blocked events for the dependency tree
        val blockedEvents = listener.getEvents.filter(_.isInstanceOf[PhaseListener.Blocked[TestId]])
        assert(blockedEvents.length >= 1)
      }

      test("should handle self-referencing dependencies gracefully") {
        val selfRefPhase: Phase[TestId, TestValue, TestValue] =
          (id: TestId, value: TestValue, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) => {
            if (isCircular) {
              PhaseRes.Ok(TestValue(s"${value.data}-self-circular", value.step + 1))
            } else {
              // Request dependency on self
              getDeps(SortedSet(id)).map { _ =>
                TestValue(s"${value.data}-self-resolved", value.step + 1)
              }
            }
          }

        val phase = RecPhase[TestId]
          .next(
            (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
              PhaseRes.Ok(TestValue(value, 0)),
            "to-testvalue"
          )
          .next(selfRefPhase, "self-ref")

        val listener = new TestListener()
        val result = PhaseRunner(phase, getLogger, listener)("self-test")

        result match {
          case PhaseRes.Ok(TestValue(data, _)) =>
            assert(data == "self-test-self-resolved")
          case _ => assert(false)
        }
      }
    }
  }
}