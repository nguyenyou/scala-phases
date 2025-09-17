# Phases Framework (Scala)

## Overview

The Phases Framework is a sophisticated computational pipeline system designed to manage complex, multi-step processing workflows with automatic dependency resolution, caching, and error handling. It provides a type-safe, functional approach to orchestrating computations that depend on each other while handling circular dependencies gracefully.

### Key Benefits

- **Automatic Dependency Resolution**: Phases can declare dependencies on other computations, and the framework automatically resolves them in the correct order
- **Intelligent Caching**: Results are cached to avoid recomputation, with support for different cache keys based on circular dependency flags
- **Robust Error Handling**: Comprehensive error propagation and aggregation across the entire pipeline
- **Circular Dependency Detection**: Automatic detection and handling of circular dependencies with appropriate fallback behavior
- **Event System**: Complete observability through a flexible listener system for monitoring phase execution

## Architecture

### Core Components

#### PhaseRes[Id, T]
A sealed trait representing the result of a phase computation:
- `Ok(value: T)` - Successful computation
- `Failure(errors: Map[Id, Either[Throwable, String]])` - Failed computation with detailed error information
- `Ignore` - Computation was skipped/ignored

#### RecPhase[Id, T]
Abstract class representing a recursive phase definition:
- `Initial` - The starting phase that passes through input unchanged
- `Next` - A transformation phase that processes the output of the previous phase

#### PhaseRunner
The core execution engine that orchestrates phase execution:
- Manages dependency resolution
- Handles circular dependency detection
- Coordinates caching and listener notifications
- Executes phases in the correct order

#### PhaseCache[Id, U]
Caching system for phase results:
- Key-based caching with `(Id, IsCircular)` tuples
- Automatic cache invalidation
- Memory-efficient storage

#### PhaseListener[Id]
Event system for monitoring phase execution:
- `Started`, `Blocked`, `Success`, `Failure`, `Ignored` events
- Pluggable listener implementations
- Real-time execution monitoring

### Data Flow

```
Input → Initial Phase → Phase 1 → Phase 2 → ... → Final Result
                ↓         ↓         ↓
            Cache     Cache     Cache
                ↓         ↓         ↓
            Listener  Listener  Listener
```

## Usage Examples

### Simple Single-Phase Example

```scala
import org.scalablytyped.converter.internal.phases._

// Define a simple transformation phase
val upperCasePhase: Phase[String, String, String] = 
  (id, value, getDeps, isCircular, logger) => {
    PhaseRes.Ok(value.toUpperCase)
  }

// Create and run the pipeline
val pipeline = RecPhase[String]().next(upperCasePhase, "uppercase")
val runner = PhaseRunner(pipeline, getLogger, listener, formatter, ordering)
val result = runner("hello") // PhaseRes.Ok("HELLO")
```

### Multi-Phase Pipeline Example

```scala
// Define multiple phases
val parsePhase: Phase[String, String, ParsedData] = 
  (id, value, getDeps, isCircular, logger) => {
    // Parse the input
    PhaseRes.Ok(ParsedData(value))
  }

val validatePhase: Phase[String, ParsedData, ValidatedData] = 
  (id, value, getDeps, isCircular, logger) => {
    if (value.isValid) PhaseRes.Ok(ValidatedData(value))
    else PhaseRes.Failure(Map(id -> Right("Validation failed")))
  }

val transformPhase: Phase[String, ValidatedData, TransformedData] = 
  (id, value, getDeps, isCircular, logger) => {
    PhaseRes.Ok(TransformedData(value.transform()))
  }

// Chain phases together
val pipeline = RecPhase[String]()
  .next(parsePhase, "parse")
  .next(validatePhase, "validate")
  .next(transformPhase, "transform")

val runner = PhaseRunner(pipeline, getLogger, listener, formatter, ordering)
val result = runner("input-data")
```

### Example with Dependencies

```scala
val dependencyPhase: Phase[String, ParsedData, EnrichedData] = 
  (id, value, getDeps, isCircular, logger) => {
    // Declare dependencies
    val dependencies = SortedSet("dependency1", "dependency2")
    
    getDeps(dependencies) match {
      case PhaseRes.Ok(depMap) =>
        // Use dependency results to enrich data
        val enriched = value.enrichWith(depMap)
        PhaseRes.Ok(enriched)
      case failure @ PhaseRes.Failure(_) => failure
      case PhaseRes.Ignore => PhaseRes.Ignore
    }
  }

val pipeline = RecPhase[String]()
  .next(parsePhase, "parse")
  .next(dependencyPhase, "enrich")
```

### Error Handling Example

```scala
val riskyPhase: Phase[String, String, ProcessedData] = 
  (id, value, getDeps, isCircular, logger) => {
    PhaseRes.attempt(id, logger) {
      if (value.contains("error")) {
        throw new RuntimeException("Processing failed")
      }
      PhaseRes.Ok(ProcessedData(value))
    }
  }

// Errors are automatically caught and wrapped in PhaseRes.Failure
val pipeline = RecPhase[String]().next(riskyPhase, "risky")
val result = runner("error-input") // PhaseRes.Failure with error details
```

### Circular Dependency Detection

```scala
val circularAwarePhase: Phase[String, String, String] = 
  (id, value, getDeps, isCircular, logger) => {
    if (isCircular) {
      // Handle circular dependency case
      logger.warn(s"Circular dependency detected for $id")
      PhaseRes.Ok(s"$value-circular")
    } else {
      // Normal processing
      PhaseRes.Ok(s"$value-normal")
    }
  }

// The framework automatically detects and flags circular dependencies
val pipeline = RecPhase[String]().next(circularAwarePhase, "circular-aware")
```

## API Reference

### PhaseRes[Id, T]
- `map[U](f: T => U): PhaseRes[Id, U]` - Transform successful results
- `flatMap[U](f: T => PhaseRes[Id, U]): PhaseRes[Id, U]` - Chain computations
- `foreach(f: T => Unit): PhaseRes[Id, Unit]` - Perform side effects

### RecPhase[Id, T]
- `next[TT](phase: Phase[Id, T, TT], name: String): RecPhase[Id, TT]` - Add a new phase
- `nextOpt(phase: Option[Phase[Id, T, T]], name: String): RecPhase[Id, T]` - Conditionally add a phase

### PhaseRunner
- `apply[Id, T](phase: RecPhase[Id, T], ...): Id => PhaseRes[Id, T]` - Create a runner function
- `go[Id, TT](phase: RecPhase[Id, TT], id: Id, circuitBreaker: List[Id], ...): PhaseRes[Id, TT]` - Execute with circuit breaker

### PhaseListener[Id]
- `on(phaseName: String, id: Id, event: PhaseEvent[Id]): Unit` - Handle phase events

## Scala-Specific Considerations

### Functional Programming Patterns
- Leverages sealed traits for type-safe result handling
- Uses higher-order functions for phase composition
- Immutable data structures throughout

### Type Safety
- Phantom types for compile-time safety
- Implicit parameters for dependency injection
- Variance annotations for flexible type relationships

### Performance
- Lazy evaluation where appropriate
- Efficient collection operations
- Minimal object allocation in hot paths

## Advanced Features

### Custom Listeners

```scala
class CustomPhaseListener extends PhaseListener[String] {
  override def on(phaseName: String, id: String, event: PhaseEvent[String]): Unit = {
    event match {
      case PhaseEvent.Started(phase) =>
        println(s"Starting phase $phase for $id")
      case PhaseEvent.Success(phase) =>
        println(s"Completed phase $phase for $id")
      case PhaseEvent.Failure(phase, errors) =>
        println(s"Failed phase $phase for $id: ${errors.mkString(", ")}")
      case PhaseEvent.Blocked(phase, dependencies) =>
        println(s"Phase $phase blocked on: ${dependencies.mkString(", ")}")
      case PhaseEvent.Ignored =>
        println(s"Phase ignored for $id")
    }
  }
}
```

### Conditional Phase Execution

```scala
val conditionalPipeline = RecPhase[String]()
  .next(parsePhase, "parse")
  .nextOpt(
    if (enableValidation) Some(validatePhase) else None,
    "validate"
  )
  .next(transformPhase, "transform")
```

### Performance Monitoring

```scala
class PerformanceListener extends PhaseListener[String] {
  private val timings = mutable.Map[String, Long]()

  override def on(phaseName: String, id: String, event: PhaseEvent[String]): Unit = {
    event match {
      case PhaseEvent.Started(_) =>
        timings(s"$phaseName-$id") = System.currentTimeMillis()
      case PhaseEvent.Success(_) | PhaseEvent.Failure(_, _) =>
        val startTime = timings.remove(s"$phaseName-$id").getOrElse(0L)
        val duration = System.currentTimeMillis() - startTime
        println(s"Phase $phaseName took ${duration}ms for $id")
      case _ => // ignore
    }
  }
}
```

## Best Practices

1. **Keep phases pure** - Avoid side effects in phase functions when possible
2. **Use descriptive phase names** - They appear in logs and error messages
3. **Handle circular dependencies** - Always check the `isCircular` flag
4. **Leverage caching** - Design phases to be cacheable for better performance
5. **Monitor execution** - Use listeners to track phase performance and errors
6. **Compose phases incrementally** - Build complex pipelines from simple, testable phases
7. **Handle errors gracefully** - Use `PhaseRes.attempt` for exception-prone operations

## Testing

### Unit Testing Phases

```scala
class PhaseSpec extends AnyFlatSpec with Matchers {
  "upperCasePhase" should "transform input to uppercase" in {
    val result = upperCasePhase("test", "hello", _ => PhaseRes.Ok(SortedMap.empty), false, mockLogger)
    result shouldBe PhaseRes.Ok("HELLO")
  }

  "dependencyPhase" should "handle missing dependencies" in {
    val getDeps: GetDeps[String, String] = _ => PhaseRes.Failure(Map("dep1" -> Right("Not found")))
    val result = dependencyPhase("test", "input", getDeps, false, mockLogger)
    result shouldBe a[PhaseRes.Failure[_, _]]
  }
}
```

## Migration Guide

When migrating from other pipeline systems:

1. **Identify your computation steps** - Each step becomes a phase
2. **Map dependencies** - Explicit dependency declarations replace implicit ordering
3. **Convert error handling** - Replace exceptions with `PhaseRes.Failure`
4. **Add caching** - Identify expensive computations that benefit from caching
5. **Implement monitoring** - Add listeners for observability