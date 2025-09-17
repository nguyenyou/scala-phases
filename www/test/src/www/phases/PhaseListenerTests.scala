package www.phases

import utest.*
import scala.collection.mutable

object PhaseListenerTests extends TestSuite {
  
  // Test helper types
  type TestId = String
  
  def tests = Tests {
    test("PhaseListener.Event") {
      test("should create Started event") {
        val event = PhaseListener.Started[TestId]("test-phase")
        event match {
          case PhaseListener.Started(phaseName) => assert(phaseName == "test-phase")
          case _ => assert(false)
        }
      }
      
      test("should create Blocked event") {
        val deps = Set("dep1", "dep2")
        val event = PhaseListener.Blocked[TestId]("test-phase", deps)
        event match {
          case PhaseListener.Blocked(phaseName, dependencies) => 
            assert(phaseName == "test-phase")
            assert(dependencies == deps)
          case _ => assert(false)
        }
      }
      
      test("should create Success event") {
        val event = PhaseListener.Success[TestId]("test-phase")
        event match {
          case PhaseListener.Success(phaseName) => assert(phaseName == "test-phase")
          case _ => assert(false)
        }
      }
      
      test("should create Failure event") {
        val errors = Map[TestId, Either[Throwable, String]]("id1" -> Right("error"))
        val event = PhaseListener.Failure[TestId]("test-phase", errors)
        event match {
          case PhaseListener.Failure(phaseName, errorMap) => 
            assert(phaseName == "test-phase")
            assert(errorMap == errors)
          case _ => assert(false)
        }
      }
      
      test("should create Ignored event") {
        val event = PhaseListener.Ignored[TestId]()
        event match {
          case PhaseListener.Ignored() => assert(true)
          case _ => assert(false)
        }
      }
    }
    
    test("PhaseListener.NoListener") {
      test("should not throw exceptions on any event") {
        val noListener = PhaseListener.NoListener[TestId]
        
        // Test all event types
        noListener.on("test", "id1", PhaseListener.Started("phase1"))
        noListener.on("test", "id2", PhaseListener.Blocked("phase2", Set("dep1")))
        noListener.on("test", "id3", PhaseListener.Success("phase3"))
        noListener.on("test", "id4", PhaseListener.Failure("phase4", Map("id4" -> Right("error"))))
        noListener.on("test", "id5", PhaseListener.Ignored())
        
        // If we reach here, no exceptions were thrown
        assert(true)
      }
      
      test("should handle null or empty parameters gracefully") {
        val noListener = PhaseListener.NoListener[TestId]
        
        noListener.on("", "", PhaseListener.Started(""))
        noListener.on(null, null, PhaseListener.Blocked("", Set.empty))
        noListener.on("test", "id", PhaseListener.Success(null))
        
        assert(true)
      }
    }
    
    test("Custom PhaseListener implementation") {
      class TestListener extends PhaseListener[TestId] {
        val events = mutable.ListBuffer[(String, TestId, PhaseListener.Event[TestId])]()
        
        override def on(phaseName: String, id: TestId, event: PhaseListener.Event[TestId]): Unit = {
          events += ((phaseName, id, event))
        }
        
        def getEvents: List[(String, TestId, PhaseListener.Event[TestId])] = events.toList
        def clear(): Unit = events.clear()
      }
      
      test("should capture all events") {
        val listener = new TestListener()
        
        listener.on("phase1", "id1", PhaseListener.Started("phase1"))
        listener.on("phase1", "id1", PhaseListener.Success("phase1"))
        listener.on("phase2", "id2", PhaseListener.Blocked("phase2", Set("dep1", "dep2")))
        
        val events = listener.getEvents
        assert(events.length == 3)
        
        events(0) match {
          case ("phase1", "id1", PhaseListener.Started("phase1")) => assert(true)
          case _ => assert(false)
        }
        
        events(1) match {
          case ("phase1", "id1", PhaseListener.Success("phase1")) => assert(true)
          case _ => assert(false)
        }
        
        events(2) match {
          case ("phase2", "id2", PhaseListener.Blocked("phase2", deps)) => 
            assert(deps == Set("dep1", "dep2"))
          case _ => assert(false)
        }
      }
      
      test("should handle error events") {
        val listener = new TestListener()
        val errors = Map[TestId, Either[Throwable, String]](
          "id1" -> Right("string error"),
          "id2" -> Left(new RuntimeException("exception error"))
        )
        
        listener.on("failing-phase", "main-id", PhaseListener.Failure("failing-phase", errors))
        
        val events = listener.getEvents
        assert(events.length == 1)
        
        events(0) match {
          case ("failing-phase", "main-id", PhaseListener.Failure("failing-phase", errorMap)) => 
            assert(errorMap == errors)
            assert(errorMap("id1") == Right("string error"))
            assert(errorMap("id2").isLeft)
          case _ => assert(false)
        }
      }
      
      test("should handle ignored events") {
        val listener = new TestListener()
        
        listener.on("ignored-phase", "id", PhaseListener.Ignored())
        
        val events = listener.getEvents
        assert(events.length == 1)
        
        events(0) match {
          case ("ignored-phase", "id", PhaseListener.Ignored()) => assert(true)
          case _ => assert(false)
        }
      }
      
      test("should support clearing events") {
        val listener = new TestListener()
        
        listener.on("phase1", "id1", PhaseListener.Started("phase1"))
        listener.on("phase2", "id2", PhaseListener.Success("phase2"))
        
        assert(listener.getEvents.length == 2)
        
        listener.clear()
        assert(listener.getEvents.isEmpty)
      }
    }
    
    test("PhaseListener event filtering") {
      class FilteringListener extends PhaseListener[TestId] {
        val startedEvents = mutable.ListBuffer[String]()
        val successEvents = mutable.ListBuffer[String]()
        val failureEvents = mutable.ListBuffer[String]()
        val blockedEvents = mutable.ListBuffer[(String, Set[TestId])]()
        val ignoredCount = mutable.Buffer[Int](0)
        
        override def on(phaseName: String, id: TestId, event: PhaseListener.Event[TestId]): Unit = {
          event match {
            case PhaseListener.Started(phase) => startedEvents += phase
            case PhaseListener.Success(phase) => successEvents += phase
            case PhaseListener.Failure(phase, _) => failureEvents += phase
            case PhaseListener.Blocked(phase, deps) => blockedEvents += ((phase, deps))
            case PhaseListener.Ignored() => ignoredCount(0) += 1
          }
        }
      }
      
      test("should filter events by type") {
        val listener = new FilteringListener()
        
        listener.on("phase1", "id1", PhaseListener.Started("phase1"))
        listener.on("phase1", "id1", PhaseListener.Success("phase1"))
        listener.on("phase2", "id2", PhaseListener.Started("phase2"))
        listener.on("phase2", "id2", PhaseListener.Failure("phase2", Map("id2" -> Right("error"))))
        listener.on("phase3", "id3", PhaseListener.Blocked("phase3", Set("dep1")))
        listener.on("phase4", "id4", PhaseListener.Ignored())
        listener.on("phase5", "id5", PhaseListener.Ignored())
        
        assert(listener.startedEvents.toList == List("phase1", "phase2"))
        assert(listener.successEvents.toList == List("phase1"))
        assert(listener.failureEvents.toList == List("phase2"))
        assert(listener.blockedEvents.toList == List(("phase3", Set("dep1"))))
        assert(listener.ignoredCount(0) == 2)
      }
    }
    
    test("PhaseListener edge cases") {
      class EdgeCaseListener extends PhaseListener[TestId] {
        var lastEvent: Option[PhaseListener.Event[TestId]] = None
        var callCount = 0
        
        override def on(phaseName: String, id: TestId, event: PhaseListener.Event[TestId]): Unit = {
          callCount += 1
          lastEvent = Some(event)
        }
      }
      
      test("should handle empty phase names") {
        val listener = new EdgeCaseListener()
        
        listener.on("", "id", PhaseListener.Started(""))
        
        assert(listener.callCount == 1)
        listener.lastEvent match {
          case Some(PhaseListener.Started("")) => assert(true)
          case _ => assert(false)
        }
      }
      
      test("should handle empty dependency sets") {
        val listener = new EdgeCaseListener()
        
        listener.on("phase", "id", PhaseListener.Blocked("phase", Set.empty))
        
        assert(listener.callCount == 1)
        listener.lastEvent match {
          case Some(PhaseListener.Blocked("phase", deps)) => assert(deps.isEmpty)
          case _ => assert(false)
        }
      }
      
      test("should handle empty error maps") {
        val listener = new EdgeCaseListener()
        
        listener.on("phase", "id", PhaseListener.Failure("phase", Map.empty))
        
        assert(listener.callCount == 1)
        listener.lastEvent match {
          case Some(PhaseListener.Failure("phase", errors)) => assert(errors.isEmpty)
          case _ => assert(false)
        }
      }
      
      test("should handle large dependency sets") {
        val listener = new EdgeCaseListener()
        val largeDeps = (1 to 1000).map(i => s"dep$i").toSet
        
        listener.on("phase", "id", PhaseListener.Blocked("phase", largeDeps))
        
        assert(listener.callCount == 1)
        listener.lastEvent match {
          case Some(PhaseListener.Blocked("phase", deps)) => 
            assert(deps.size == 1000)
            assert(deps.contains("dep1"))
            assert(deps.contains("dep1000"))
          case _ => assert(false)
        }
      }
    }
  }
}
