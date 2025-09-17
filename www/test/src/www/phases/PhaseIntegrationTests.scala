package www.phases

import www.logging.{Formatter, Logger}
import utest.*
import scala.collection.immutable.SortedSet
import scala.collection.mutable

object PhaseIntegrationTests extends TestSuite {
  
  // Test helper types
  type TestId = String
  case class TestData(content: String, version: Int = 0, processed: Boolean = false)
  
  // Implicit instances for TestId
  implicit val testIdFormatter: Formatter[TestId] = Formatter.StringFormatter
  implicit val testIdOrdering: Ordering[TestId] = Ordering.String
  
  // Mock logger factory
  def getLogger(id: TestId): Logger[Unit] = Logger.DevNull
  
  // Test listener to capture events
  class IntegrationTestListener extends PhaseListener[TestId] {
    val events = mutable.ListBuffer[(String, TestId, PhaseListener.Event[TestId])]()
    
    override def on(phaseName: String, id: TestId, event: PhaseListener.Event[TestId]): Unit = {
      events += ((phaseName, id, event))
    }
    
    def clear(): Unit = events.clear()
    def getEvents: List[(String, TestId, PhaseListener.Event[TestId])] = events.toList
    def getEventsForPhase(phaseName: String): List[PhaseListener.Event[TestId]] = 
      events.filter(_._1 == phaseName).map(_._3).toList
  }
  
  // Phase implementations for testing
  def initializationPhase: Phase[TestId, TestId, TestData] =
    (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestData], isCircular: IsCircular, logger: Logger[Unit]) =>
      PhaseRes.Ok(TestData(s"initialized-$value", 1))
  
  def processingPhase: Phase[TestId, TestData, TestData] =
    (id: TestId, value: TestData, getDeps: GetDeps[TestId, TestData], isCircular: IsCircular, logger: Logger[Unit]) =>
      PhaseRes.Ok(value.copy(content = value.content.toUpperCase, version = value.version + 1, processed = true))
  
  def dependencyPhase(deps: Set[TestId]): Phase[TestId, TestData, TestData] =
    (id: TestId, value: TestData, getDeps: GetDeps[TestId, TestData], isCircular: IsCircular, logger: Logger[Unit]) => {
      // Only request dependencies for the main ID, not for dependency IDs themselves
      if (deps.nonEmpty && !deps.contains(id)) {
        getDeps(SortedSet.from(deps)).map { depMap =>
          val depCount = depMap.size
          value.copy(
            content = s"${value.content}-with-${depCount}-deps",
            version = value.version + 1
          )
        }
      } else {
        PhaseRes.Ok(value.copy(version = value.version + 1))
      }
    }
  
  def conditionalPhase(shouldProcess: TestId => Boolean): Phase[TestId, TestData, TestData] =
    (id: TestId, value: TestData, getDeps: GetDeps[TestId, TestData], isCircular: IsCircular, logger: Logger[Unit]) => {
      if (shouldProcess(id)) {
        PhaseRes.Ok(value.copy(content = s"${value.content}-conditional", version = value.version + 1))
      } else {
        PhaseRes.Ignore()
      }
    }
  
  def failingPhase(shouldFail: TestId => Boolean): Phase[TestId, TestData, TestData] =
    (id: TestId, value: TestData, getDeps: GetDeps[TestId, TestData], isCircular: IsCircular, logger: Logger[Unit]) => {
      if (shouldFail(id)) {
        PhaseRes.Failure(Map(id -> Right(s"Intentional failure for $id")))
      } else {
        PhaseRes.Ok(value.copy(content = s"${value.content}-success", version = value.version + 1))
      }
    }
  
  def circularAwarePhase: Phase[TestId, TestData, TestData] =
    (id: TestId, value: TestData, getDeps: GetDeps[TestId, TestData], isCircular: IsCircular, logger: Logger[Unit]) => {
      val suffix = if (isCircular) "-circular" else "-normal"
      PhaseRes.Ok(value.copy(content = s"${value.content}$suffix", version = value.version + 1))
    }
  
  def tests = Tests {
    test("Simple linear pipeline") {
      test("should execute phases in sequence") {
        val pipeline = RecPhase[TestId]
          .next(initializationPhase, "initialization")
          .next(processingPhase, "processing")
        
        val listener = new IntegrationTestListener()
        val result = PhaseRunner(pipeline, getLogger, listener)("test-item")
        
        result match {
          case PhaseRes.Ok(data) => 
            assert(data.content == "INITIALIZED-TEST-ITEM")
            assert(data.version == 2)
            assert(data.processed == true)
          case _ => assert(false)
        }
        
        // Verify events were generated
        val events = listener.getEvents
        assert(events.length >= 4) // At least Started and Success for each phase
        
        val initEvents = listener.getEventsForPhase("initialization")
        val procEvents = listener.getEventsForPhase("processing")
        
        assert(initEvents.exists(_.isInstanceOf[PhaseListener.Started[TestId]]))
        assert(initEvents.exists(_.isInstanceOf[PhaseListener.Success[TestId]]))
        assert(procEvents.exists(_.isInstanceOf[PhaseListener.Started[TestId]]))
        assert(procEvents.exists(_.isInstanceOf[PhaseListener.Success[TestId]]))
      }
      
      test("should handle ignored phases") {
        val pipeline = RecPhase[TestId]
          .next(initializationPhase, "initialization")
          .next(conditionalPhase(_ == "should-ignore"), "conditional")
        
        val listener = new IntegrationTestListener()
        val result = PhaseRunner(pipeline, getLogger, listener)("test-item")
        
        result match {
          case PhaseRes.Ignore() => assert(true)
          case _ => assert(false)
        }
        
        val conditionalEvents = listener.getEventsForPhase("conditional")
        assert(conditionalEvents.exists(_.isInstanceOf[PhaseListener.Ignored[TestId]]))
      }
      
      test("should handle failing phases") {
        val pipeline = RecPhase[TestId]
          .next(initializationPhase, "initialization")
          .next(failingPhase(_ == "test-item"), "failing")
        
        val listener = new IntegrationTestListener()
        val result = PhaseRunner(pipeline, getLogger, listener)("test-item")
        
        result match {
          case PhaseRes.Failure(errors) => 
            assert(errors.contains("test-item"))
            assert(errors("test-item") == Right("Intentional failure for test-item"))
          case _ => assert(false)
        }
        
        val failingEvents = listener.getEventsForPhase("failing")
        assert(failingEvents.exists(_.isInstanceOf[PhaseListener.Failure[TestId]]))
      }
    }
    
    test("Complex dependency pipeline") {
      test("should resolve dependencies correctly") {
        val pipeline = RecPhase[TestId]
          .next(initializationPhase, "initialization")
          .next(dependencyPhase(Set("dep1", "dep2")), "dependencies")
          .next(processingPhase, "processing")
        
        val listener = new IntegrationTestListener()
        val result = PhaseRunner(pipeline, getLogger, listener)("main")
        
        result match {
          case PhaseRes.Ok(data) => 
            assert(data.content.contains("INITIALIZED-MAIN"))
            assert(data.content.contains("WITH-2-DEPS"))
            assert(data.processed == true)
            assert(data.version == 3)
          case _ => assert(false)
        }
        
        // Should have blocked events for dependency resolution
        val depEvents = listener.getEventsForPhase("dependencies")
        assert(depEvents.exists {
          case PhaseListener.Blocked(_, deps) => deps == Set("dep1", "dep2")
          case _ => false
        })
      }
      
      test("should handle empty dependencies") {
        val pipeline = RecPhase[TestId]
          .next(initializationPhase, "initialization")
          .next(dependencyPhase(Set.empty), "no-dependencies")
        
        val listener = new IntegrationTestListener()
        val result = PhaseRunner(pipeline, getLogger, listener)("main")
        
        result match {
          case PhaseRes.Ok(data) => 
            assert(data.content == "initialized-main")
            assert(data.version == 2)
          case _ => assert(false)
        }
        
        // Should not have blocked events for empty dependencies
        val depEvents = listener.getEventsForPhase("no-dependencies")
        assert(!depEvents.exists(_.isInstanceOf[PhaseListener.Blocked[TestId]]))
      }
    }
    
    test("Circular dependency handling") {
      test("should detect and handle circular dependencies") {
        val pipeline = RecPhase[TestId]
          .next(initializationPhase, "initialization")
          .next(circularAwarePhase, "circular-aware")
        
        val listener = new IntegrationTestListener()
        
        // Simulate circular dependency by calling go with the same id in circuit breaker
        val result = PhaseRunner.go(pipeline, "test-item", List("test-item"), getLogger, listener)
        
        result match {
          case PhaseRes.Ok(data) => 
            assert(data.content == "initialized-test-item-circular")
            assert(data.version == 2)
          case _ => assert(false)
        }
      }
      
      test("should handle non-circular execution") {
        val pipeline = RecPhase[TestId]
          .next(initializationPhase, "initialization")
          .next(circularAwarePhase, "circular-aware")
        
        val listener = new IntegrationTestListener()
        val result = PhaseRunner(pipeline, getLogger, listener)("test-item")
        
        result match {
          case PhaseRes.Ok(data) => 
            assert(data.content == "initialized-test-item-normal")
            assert(data.version == 2)
          case _ => assert(false)
        }
      }
    }
    
    test("Mixed success/failure/ignore pipeline") {
      test("should handle complex scenarios") {
        val pipeline = RecPhase[TestId]
          .next(initializationPhase, "initialization")
          .next(conditionalPhase(id => id.contains("process")), "conditional")
          .next(failingPhase(id => id.contains("fail")), "potential-failure")
          .next(processingPhase, "final-processing")
        
        val listener = new IntegrationTestListener()
        
        // Test successful path
        val successResult = PhaseRunner(pipeline, getLogger, listener)("process-success")
        successResult match {
          case PhaseRes.Ok(data) => 
            assert(data.processed == true)
            assert(data.content.contains("CONDITIONAL"))
          case _ => assert(false)
        }
        
        listener.clear()
        
        // Test ignored path
        val ignoreResult = PhaseRunner(pipeline, getLogger, listener)("ignore-item")
        ignoreResult match {
          case PhaseRes.Ignore() => assert(true)
          case _ => assert(false)
        }
        
        listener.clear()
        
        // Test failure path
        val failResult = PhaseRunner(pipeline, getLogger, listener)("process-fail")
        failResult match {
          case PhaseRes.Failure(errors) => 
            assert(errors.contains("process-fail"))
          case _ => assert(false)
        }
      }
    }
    
    test("Caching behavior in pipelines") {
      test("should cache results across multiple executions") {
        var initCount = 0
        var processCount = 0
        
        val countingInitPhase: Phase[TestId, TestId, TestData] =
          (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestData], isCircular: IsCircular, logger: Logger[Unit]) => {
            initCount += 1
            PhaseRes.Ok(TestData(s"init-$value-$initCount", 1))
          }
        
        val countingProcessPhase: Phase[TestId, TestData, TestData] =
          (id: TestId, value: TestData, getDeps: GetDeps[TestId, TestData], isCircular: IsCircular, logger: Logger[Unit]) => {
            processCount += 1
            PhaseRes.Ok(value.copy(content = s"${value.content}-processed-$processCount", processed = true))
          }
        
        val pipeline = RecPhase[TestId]
          .next(countingInitPhase, "counting-init")
          .next(countingProcessPhase, "counting-process")
        
        val listener = new IntegrationTestListener()
        
        // First execution
        val result1 = PhaseRunner(pipeline, getLogger, listener)("cache-test")
        assert(initCount == 1)
        assert(processCount == 1)
        
        // Second execution with same pipeline should use cache
        val result2 = PhaseRunner(pipeline, getLogger, listener)("cache-test")
        assert(initCount == 1) // Should not increment
        assert(processCount == 1) // Should not increment
        
        // Results should be identical
        assert(result1 == result2)
      }
    }
  }
}