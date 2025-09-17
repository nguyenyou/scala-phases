package www.phases

import www.logging.Logger
import utest.*
import scala.collection.immutable.SortedSet

object RecPhaseTests extends TestSuite {
  
  // Test helper types
  type TestId = String
  case class TestValue(data: String, step: Int = 0)
  
  // Mock logger for testing
  val mockLogger: Logger[Unit] = Logger.DevNull
  
  // Helper phase functions
  def identityPhase[Id, T]: Phase[Id, T, T] = 
    (id: Id, value: T, getDeps: GetDeps[Id, T], isCircular: IsCircular, logger: Logger[Unit]) =>
      PhaseRes.Ok(value)
  
  def transformPhase[Id](transform: String => String): Phase[Id, TestValue, TestValue] =
    (id: Id, value: TestValue, getDeps: GetDeps[Id, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
      PhaseRes.Ok(TestValue(transform(value.data), value.step + 1))
  
  def failingPhase[Id, T, TT]: Phase[Id, T, TT] =
    (id: Id, value: T, getDeps: GetDeps[Id, TT], isCircular: IsCircular, logger: Logger[Unit]) =>
      PhaseRes.Failure(Map(id -> Right("Test failure")))
  
  def ignoringPhase[Id, T, TT]: Phase[Id, T, TT] =
    (id: Id, value: T, getDeps: GetDeps[Id, TT], isCircular: IsCircular, logger: Logger[Unit]) =>
      PhaseRes.Ignore()
  
  def dependencyPhase(deps: Set[TestId]): Phase[TestId, TestValue, TestValue] =
    (id: TestId, value: TestValue, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) => {
      if (deps.nonEmpty) {
        implicit val ordering: Ordering[TestId] = Ordering.String
        getDeps(SortedSet.from(deps)).map { depMap =>
          val depCount = depMap.size
          TestValue(s"${value.data}-with-${depCount}-deps", value.step + 1)
        }
      } else {
        PhaseRes.Ok(TestValue(value.data, value.step + 1))
      }
    }
  
  def tests = Tests {
    test("RecPhase.Initial") {
      test("should create Initial phase") {
        val initial = RecPhase[TestId]
        assert(initial.isInstanceOf[RecPhase.Initial[TestId]])
      }
      
      test("should have correct type parameters") {
        val initial = RecPhase[TestId]
        // Type should be RecPhase[TestId, TestId] - we can't test type equality at runtime
        // but the compilation itself verifies this
        assert(initial.isInstanceOf[RecPhase[TestId, TestId]])
      }
      
      test("should work with apply factory method") {
        val initial1 = RecPhase[TestId]
        val initial2 = RecPhase.apply[TestId]
        assert(initial1.getClass == initial2.getClass)
      }
    }
    
    test("RecPhase.Next") {
      test("should create Next phase from Initial") {
        val initial = RecPhase[TestId]
        val next = initial.next(identityPhase, "identity")
        
        assert(next.isInstanceOf[RecPhase.Next[TestId, TestId, TestId]])
        next match {
          case nextPhase: RecPhase.Next[TestId, TestId, TestId] =>
            // We can't access the internal fields directly, but we can verify the type
            assert(nextPhase != null)
          case _ => assert(false)
        }
      }
      
      test("should chain multiple phases") {
        val initial = RecPhase[TestId]

        // First convert TestId to TestValue
        val toTestValuePhase: Phase[TestId, TestId, TestValue] =
          (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
            PhaseRes.Ok(TestValue(value, 0))

        val phase1 = initial.next(toTestValuePhase, "to-testvalue")
        val phase2 = phase1.next(transformPhase(_.toUpperCase), "uppercase")
        val phase3 = phase2.next(transformPhase(_ + "!"), "exclamation")

        assert(phase1.isInstanceOf[RecPhase.Next[TestId, TestId, TestValue]])
        assert(phase2.isInstanceOf[RecPhase.Next[TestId, TestValue, TestValue]])
        assert(phase3.isInstanceOf[RecPhase.Next[TestId, TestValue, TestValue]])
      }
      
      test("should preserve type transformations") {
        val initial = RecPhase[TestId]
        val stringPhase = initial.next(
          (id: TestId, value: TestId, getDeps: GetDeps[TestId, String], isCircular: IsCircular, logger: Logger[Unit]) =>
            PhaseRes.Ok(s"processed-$value"),
          "to-string"
        )
        
        assert(stringPhase.isInstanceOf[RecPhase.Next[TestId, TestId, String]])
        
        val intPhase = stringPhase.next(
          (id: TestId, value: String, getDeps: GetDeps[TestId, Int], isCircular: IsCircular, logger: Logger[Unit]) =>
            PhaseRes.Ok(value.length),
          "to-int"
        )
        
        assert(intPhase.isInstanceOf[RecPhase.Next[TestId, String, Int]])
      }
    }
    
    test("RecPhase.nextOpt") {
      test("should add phase when Some is provided") {
        val initial = RecPhase[TestId]
        val maybePhase = Some(identityPhase[TestId, TestId])
        val result = initial.nextOpt(maybePhase, "optional")

        assert(result.isInstanceOf[RecPhase.Next[TestId, TestId, TestId]])
        assert(result != initial)
      }
      
      test("should return same phase when None is provided") {
        val initial = RecPhase[TestId]
        val result = initial.nextOpt(None, "skipped")
        
        assert(result == initial)
        assert(result.isInstanceOf[RecPhase.Initial[TestId]])
      }
      
      test("should work with chained nextOpt calls") {
        val initial = RecPhase[TestId]

        // First convert to TestValue
        val toTestValuePhase: Phase[TestId, TestId, TestValue] =
          (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
            PhaseRes.Ok(TestValue(value, 0))

        val phase1 = initial.next(toTestValuePhase, "to-testvalue")
        val phase2 = phase1.nextOpt(Some(transformPhase(_.toUpperCase)), "uppercase")
        val phase3 = phase2.nextOpt(None, "skipped")
        val phase4 = phase3.nextOpt(Some(transformPhase(_ + "!")), "exclamation")

        // phase3 should be same as phase2 since None was passed
        assert(phase3 == phase2)

        // phase4 should be different from phase3
        assert(phase4 != phase3)
        assert(phase4.isInstanceOf[RecPhase.Next[TestId, TestValue, TestValue]])
      }
    }
    
    test("RecPhase composition patterns") {
      test("should support complex phase pipelines") {
        // First convert to TestValue
        val toTestValuePhase: Phase[TestId, TestId, TestValue] =
          (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
            PhaseRes.Ok(TestValue(value, 0))

        val pipeline = RecPhase[TestId]
          .next(toTestValuePhase, "to-testvalue")
          .next(transformPhase(_.toLowerCase), "lowercase")
          .next(transformPhase(_.capitalize), "capitalize")
          .nextOpt(Some(transformPhase(_ + " World")), "greeting")
          .nextOpt(None, "skipped-step")
          .next(transformPhase(_.replace(" ", "_")), "underscore")

        // Should be a Next phase
        assert(pipeline.isInstanceOf[RecPhase.Next[TestId, TestValue, TestValue]])
      }
      
      test("should handle different phase result types") {
        val initial = RecPhase[TestId]
        
        // Test with failing phase
        val failingPipeline = initial.next(failingPhase, "failing")
        assert(failingPipeline.isInstanceOf[RecPhase.Next[TestId, TestId, Nothing]])
        
        // Test with ignoring phase  
        val ignoringPipeline = initial.next(ignoringPhase, "ignoring")
        assert(ignoringPipeline.isInstanceOf[RecPhase.Next[TestId, TestId, Nothing]])
      }
      
      test("should support dependency-based phases") {
        val initial = RecPhase[TestId]

        // First convert to TestValue
        val toTestValuePhase: Phase[TestId, TestId, TestValue] =
          (id: TestId, value: TestId, getDeps: GetDeps[TestId, TestValue], isCircular: IsCircular, logger: Logger[Unit]) =>
            PhaseRes.Ok(TestValue(value, 0))

        val phase1 = initial.next(toTestValuePhase, "to-testvalue")
        val depPhase = phase1.next(dependencyPhase(Set("dep1", "dep2")), "with-deps")

        assert(depPhase.isInstanceOf[RecPhase.Next[TestId, TestValue, TestValue]])
      }
    }
    
    test("RecPhase type safety") {
      test("should maintain type safety through transformations") {
        val initial: RecPhase[TestId, TestId] = RecPhase[TestId]
        val stringPhase: RecPhase[TestId, String] = initial.next(
          (id: TestId, value: TestId, getDeps: GetDeps[TestId, String], isCircular: IsCircular, logger: Logger[Unit]) =>
            PhaseRes.Ok(s"string-$value"),
          "to-string"
        )
        val intPhase: RecPhase[TestId, Int] = stringPhase.next(
          (id: TestId, value: String, getDeps: GetDeps[TestId, Int], isCircular: IsCircular, logger: Logger[Unit]) =>
            PhaseRes.Ok(value.length),
          "to-int"
        )
        
        // Verify types are correctly maintained through compilation
        // Note: We can't directly test the type equality at runtime for generic types,
        // but the compilation itself verifies type safety
        assert(initial.isInstanceOf[RecPhase[TestId, TestId]])
        assert(stringPhase.isInstanceOf[RecPhase[TestId, String]])
        assert(intPhase.isInstanceOf[RecPhase[TestId, Int]])
      }
    }
    
    test("RecPhase edge cases") {
      test("should handle empty phase names") {
        val initial = RecPhase[TestId]
        val phase = initial.next(identityPhase, "")
        
        phase match {
          case RecPhase.Next(_, _, _, name) => assert(name == "")
          case _ => assert(false)
        }
      }
      
      test("should handle very long phase names") {
        val longName = "a" * 1000
        val initial = RecPhase[TestId]
        val phase = initial.next(identityPhase, longName)
        
        phase match {
          case RecPhase.Next(_, _, _, name) => assert(name == longName)
          case _ => assert(false)
        }
      }
      
      test("should create separate cache instances for each phase") {
        val initial = RecPhase[TestId]
        val phase1 = initial.next(identityPhase, "phase1")
        val phase2 = initial.next(identityPhase, "phase2")

        // Different phases should be different instances
        assert(phase1 != phase2)
        assert(phase1.isInstanceOf[RecPhase.Next[TestId, TestId, TestId]])
        assert(phase2.isInstanceOf[RecPhase.Next[TestId, TestId, TestId]])
      }
    }
  }
}