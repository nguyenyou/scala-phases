package www.phases

import www.logging.{Formatter, Logger}
import utest.*
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.collection.mutable

object DocumentationExamplesTests extends TestSuite {
  
  // Test data types from README examples
  type TestId = String
  case class ParsedData(content: String)
  case class ValidatedData(content: String, isValid: Boolean)
  case class TransformedData(result: String)
  case class ProcessedData(processed: String)
  case class EnrichedData(content: String, enrichments: List[String])

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
    def size: Int = events.size
  }

  // Helper functions
  def enrichDataWith(data: ParsedData, deps: SortedMap[String, Any]): EnrichedData =
    EnrichedData(data.content, deps.keys.toList)

  def tests = Tests {
    test("Simple single-phase example from README") {
      // Example from README: Simple Single-Phase Example
      val upperCasePhase: Phase[String, String, String] = 
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(value.toUpperCase)
        }

      val pipeline = RecPhase[String].next(upperCasePhase, "uppercase")
      val listener = new TestListener
      val runner = PhaseRunner(pipeline, getLogger, listener)
      val result = runner("hello")

      assert(result == PhaseRes.Ok("HELLO"))
    }

    test("Multi-phase pipeline example from README") {
      // Example from README: Multi-Phase Pipeline Example
      val parsePhase: Phase[String, String, ParsedData] = 
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(ParsedData(value))
        }

      val validatePhase: Phase[String, ParsedData, ValidatedData] = 
        (id, value, getDeps, isCircular, logger) => {
          if (value.content.nonEmpty) {
            PhaseRes.Ok(ValidatedData(value.content, isValid = true))
          } else {
            PhaseRes.Failure(Map(id -> Right("Validation failed")))
          }
        }

      val transformPhase: Phase[String, ValidatedData, TransformedData] = 
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(TransformedData(value.content.toUpperCase))
        }

      val pipeline = RecPhase[String]
        .next(parsePhase, "parse")
        .next(validatePhase, "validate")
        .next(transformPhase, "transform")

      val listener = new TestListener
      val runner = PhaseRunner(pipeline, getLogger, listener)
      val result = runner("input-data")

      result match {
        case PhaseRes.Ok(transformed) => assert(transformed.result == "INPUT-DATA")
        case other => assert(false)
      }
    }

    test("Example with dependencies from README") {
      // Example from README: Example with Dependencies
      val parsePhase: Phase[String, String, ParsedData] = 
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(ParsedData(value))
        }

      val dependencyPhase: Phase[String, ParsedData, EnrichedData] = 
        (id, value, getDeps, isCircular, logger) => {
          // Only request dependencies if this is not a dependency itself
          if (id == "test-input") {
            val dependencies = SortedSet("dependency1", "dependency2")
            
            getDeps(dependencies).map { depMap =>
              enrichDataWith(value, depMap)
            }
          } else {
            // For dependencies, just return the parsed data as enriched data
            PhaseRes.Ok(EnrichedData(value.content, List.empty))
          }
        }

      val pipeline = RecPhase[String]
        .next(parsePhase, "parse")
        .next(dependencyPhase, "enrich")

      val listener = new TestListener
      val runner = PhaseRunner(pipeline, getLogger, listener)
      val result = runner("test-input")

      result match {
        case PhaseRes.Ok(enriched) =>
          assert(enriched.content == "test-input")
          assert(enriched.enrichments.isInstanceOf[List[?]])
        case other => assert(false)
      }
    }

    test("Error handling example from README") {
      // Example from README: Error Handling Example
      val riskyPhase: Phase[String, String, ProcessedData] = 
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.attempt(id, logger, {
            if (value.contains("error")) {
              throw new RuntimeException("Processing failed")
            }
            PhaseRes.Ok(ProcessedData(value))
          })
        }

      val pipeline = RecPhase[String].next(riskyPhase, "risky")
      val listener = new TestListener
      val runner = PhaseRunner(pipeline, getLogger, listener)
      
      // Test successful case
      val successResult = runner("good-input")
      successResult match {
        case PhaseRes.Ok(processed) => assert(processed.processed == "good-input")
        case other => assert(false)
      }

      // Test error case
      val errorResult = runner("error-input")
      errorResult match {
        case PhaseRes.Failure(errors) => assert(errors.nonEmpty)
        case other => assert(false)
      }
    }

    test("Validation failure example") {
      // Test validation failure from multi-phase example
      val parsePhase: Phase[String, String, ParsedData] = 
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(ParsedData(value))
        }

      val validatePhase: Phase[String, ParsedData, ValidatedData] = 
        (id, value, getDeps, isCircular, logger) => {
          if (value.content.nonEmpty) {
            PhaseRes.Ok(ValidatedData(value.content, isValid = true))
          } else {
            PhaseRes.Failure(Map(id -> Right("Validation failed")))
          }
        }

      val pipeline = RecPhase[String]
        .next(parsePhase, "parse")
        .next(validatePhase, "validate")

      val listener = new TestListener
      val runner = PhaseRunner(pipeline, getLogger, listener)
      val result = runner("") // Empty string should fail validation

      result match {
        case PhaseRes.Failure(errors) =>
          assert(errors.nonEmpty)
          assert(errors.values.head == Right("Validation failed"))
        case other => assert(false)
      }
    }

    test("Circular dependency detection from README") {
      // Example from README: Circular Dependency Detection
      val circularAwarePhase: Phase[String, String, String] = 
        (id, value, getDeps, isCircular, logger) => {
          if (isCircular) {
            logger.warn(s"Circular dependency detected for $id")
            PhaseRes.Ok(s"$value-circular")
          } else {
            PhaseRes.Ok(s"$value-normal")
          }
        }

      val pipeline = RecPhase[String].next(circularAwarePhase, "circular-aware")
      val listener = new TestListener
      
      // Test normal execution
      val runner = PhaseRunner(pipeline, getLogger, listener)
      val normalResult = runner("test")
      assert(normalResult == PhaseRes.Ok("test-normal"))

      // Test circular execution
      val circularResult = PhaseRunner.go(pipeline, "test", List("test"), getLogger, listener)
      assert(circularResult == PhaseRes.Ok("test-circular"))
    }

    test("Custom listener example from README") {
      // Example from README: Custom Listeners
      class CustomPhaseListener extends PhaseListener[String] {
        var events: List[(String, String, PhaseListener.Event[String])] = List.empty

        override def on(phaseName: String, id: String, event: PhaseListener.Event[String]): Unit = {
          events = (phaseName, id, event) :: events

          event match {
            case PhaseListener.Started(phase) =>
              // println(s"Starting phase $phase for $id")
            case PhaseListener.Success(phase) =>
              // println(s"Completed phase $phase for $id")
            case PhaseListener.Failure(phase, errors) =>
              // println(s"Failed phase $phase for $id: ${errors.mkString(", ")}")
            case PhaseListener.Blocked(phase, dependencies) =>
              // println(s"Phase $phase blocked on: ${dependencies.mkString(", ")}")
            case PhaseListener.Ignored() =>
              // println(s"Phase ignored for $id")
          }
        }
      }

      val simplePhase: Phase[String, String, String] =
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(value.toUpperCase)
        }

      val pipeline = RecPhase[String].next(simplePhase, "test-phase")
      val customListener = new CustomPhaseListener
      val runner = PhaseRunner(pipeline, getLogger, customListener)

      val result = runner("test")

      assert(result == PhaseRes.Ok("TEST"))
      assert(customListener.events.nonEmpty)

      // Verify we got Started and Success events
      val startedEvents = customListener.events.filter(_._3.isInstanceOf[PhaseListener.Started[String]])
      val successEvents = customListener.events.filter(_._3.isInstanceOf[PhaseListener.Success[String]])
      assert(startedEvents.nonEmpty)
      assert(successEvents.nonEmpty)
    }

    test("Conditional phase execution from README") {
      // Example from README: Conditional Phase Execution
      val parsePhase: Phase[String, String, ParsedData] =
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(ParsedData(value))
        }

      val validatePhase: Phase[String, ParsedData, ParsedData] =
        (id, value, getDeps, isCircular, logger) => {
          if (value.content.contains("invalid")) {
            PhaseRes.Failure(Map(id -> Right("Validation failed")))
          } else {
            PhaseRes.Ok(value)
          }
        }

      val transformPhase: Phase[String, ParsedData, TransformedData] =
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(TransformedData(value.content.toUpperCase))
        }

      // Test with validation enabled
      val enableValidation = true
      val conditionalPipeline = RecPhase[String]
        .next(parsePhase, "parse")
        .nextOpt(
          if (enableValidation) Some(validatePhase) else None,
          "validate"
        )
        .next(transformPhase, "transform")

      val listener = new TestListener
      val runner = PhaseRunner(conditionalPipeline, getLogger, listener)

      // Test successful case
      val successResult = runner("valid-input")
      successResult match {
        case PhaseRes.Ok(_) => assert(true)
        case other => assert(false)
      }

      // Test validation failure
      val failureResult = runner("invalid-input")
      failureResult match {
        case PhaseRes.Failure(_) => assert(true)
        case other => assert(false)
      }

      // Test with validation disabled
      val disabledValidationPipeline = RecPhase[String]
        .next(parsePhase, "parse")
        .nextOpt(None, "validate")
        .next(transformPhase, "transform")

      val disabledRunner = PhaseRunner(disabledValidationPipeline, getLogger, listener)
      val disabledResult = disabledRunner("invalid-input")
      disabledResult match {
        case PhaseRes.Ok(_) => assert(true) // Should succeed since validation is skipped
        case other => assert(false)
      }
    }

    test("Performance monitoring example from README") {
      // Example from README: Performance Monitoring
      class PerformanceListener extends PhaseListener[String] {
        private val timings = mutable.Map[String, Long]()
        var measurements: List[(String, String, Long)] = List.empty

        override def on(phaseName: String, id: String, event: PhaseListener.Event[String]): Unit = {
          val key = s"$phaseName-$id"

          event match {
            case PhaseListener.Started(_) =>
              timings(key) = System.currentTimeMillis()
            case PhaseListener.Success(_) | PhaseListener.Failure(_, _) =>
              val startTime = timings.remove(key).getOrElse(0L)
              val duration = System.currentTimeMillis() - startTime
              measurements = (phaseName, id, duration) :: measurements
            case _ => // ignore
          }
        }
      }

      val slowPhase: Phase[String, String, String] =
        (id, value, getDeps, isCircular, logger) => {
          // Simulate some work
          PhaseRes.Ok(value.toUpperCase)
        }

      val pipeline = RecPhase[String].next(slowPhase, "slow-phase")
      val perfListener = new PerformanceListener
      val runner = PhaseRunner(pipeline, getLogger, perfListener)

      val result = runner("test")

      assert(result == PhaseRes.Ok("TEST"))
      assert(perfListener.measurements.length == 1)
      assert(perfListener.measurements.head._1 == "slow-phase")
      assert(perfListener.measurements.head._3 >= 0L)
    }

    test("Unit testing phases example from README") {
      // Example from README: Unit Testing Phases
      val upperCasePhase: Phase[String, String, String] =
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(value.toUpperCase)
        }

      val mockGetDeps: GetDeps[String, String] = _ => PhaseRes.Ok(SortedMap.empty)
      val result = upperCasePhase("test", "hello", mockGetDeps, false, Logger.DevNull)

      assert(result == PhaseRes.Ok("HELLO"))
    }

    test("Dependency testing example from README") {
      // Example from README: Unit Testing Phases - dependency handling
      val dependencyPhase: Phase[String, String, String] =
        (id, value, getDeps, isCircular, logger) => {
          val deps = SortedSet("dep1")
          getDeps(deps) match {
            case PhaseRes.Failure(errors) => PhaseRes.Failure(errors)
            case _ => PhaseRes.Ok(value)
          }
        }

      val mockGetDeps: GetDeps[String, String] = _ => PhaseRes.Failure(Map("dep1" -> Right("Not found")))
      val result = dependencyPhase("test", "input", mockGetDeps, false, Logger.DevNull)

      result match {
        case PhaseRes.Failure(_) => assert(true)
        case other => assert(false)
      }
    }

    test("Integration testing example from README") {
      // Example from README: Integration Testing
      val parsePhase: Phase[String, String, ParsedData] =
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(ParsedData(value))
        }

      val validatePhase: Phase[String, ParsedData, ValidatedData] =
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(ValidatedData(value.content, isValid = true))
        }

      val transformPhase: Phase[String, ValidatedData, TransformedData] =
        (id, value, getDeps, isCircular, logger) => {
          PhaseRes.Ok(TransformedData(value.content.toUpperCase))
        }

      val pipeline = RecPhase[String]
        .next(parsePhase, "parse")
        .next(validatePhase, "validate")
        .next(transformPhase, "transform")

      val listener = new TestListener
      val runner = PhaseRunner(pipeline, getLogger, listener)

      val result = runner("test-input")

      result match {
        case PhaseRes.Ok(transformed) => assert(transformed.result == "TEST-INPUT")
        case other => assert(false)
      }
      assert(listener.size > 0)
    }
  }
}