# Phases Framework Tutorial

This tutorial walks you through building pipelines with the `www.phases` framework, starting from the very first phase and advancing toward complex dependency graphs, custom listeners, and testing strategies. Every section contains copy-paste ready snippets that compile inside this repository.

## Prerequisites

- Scala 3 toolchain (Mill handles it for you).
- Required givens in scope when you run the examples:

```scala
import www.logging.{Formatter, Logger}
import www.phases._

given Formatter[String] = Formatter.StringFormatter
given Ordering[String]  = Ordering.String

val baseLogger: Logger[Unit] = Logger.DevNull
def getLogger(id: String): Logger[Unit] = baseLogger
def listener: PhaseListener[String] = PhaseListener.NoListener
```

- Build commands:
  - `./mill www.compile` – compile the module and check formatting.
  - `./mill www.test` – run the uTest suites (mirroring `www/test/src/www/phases`).

> **Tip:** Drop the snippets into a scratch `@main` under `www/src/www` or into the Scala REPL (`./mill -i repl`) to experiment.

## 1. First Phase (Beginner)

Define a single transformation that uppercases incoming values. Paste the whole block into a Scala file after the prereq imports/givens.

```scala
val uppercasePhase: Phase[String, String, String] =
  (id, value, _ /* deps */, _ /* isCircular */, _ /* logger */) =>
    PhaseRes.Ok(value.toUpperCase)

val basicPipeline: RecPhase[String, String] =
  RecPhase[String].next(uppercasePhase, "uppercase")

val runner = PhaseRunner(basicPipeline, getLogger, listener)
val result = runner("hello")
assert(result == PhaseRes.Ok("HELLO"))
```

What happened:
- `RecPhase[String]` seeds the pipeline with an `Initial` phase that just forwards the ID.
- `next` attaches our transformation with a phase name (`"uppercase"`).
- `PhaseRunner` executes the pipeline and returns a `PhaseRes` describing success/failure/ignore states.

## 2. Linking Phases (Beginner → Intermediate)

Chain multiple phases to model a small ETL pipeline.

```scala
case class Parsed(content: String)
case class Validated(content: String, isValid: Boolean)
case class Transformed(content: String)

val parse: Phase[String, String, Parsed] =
  (_, raw, _, _, _) => PhaseRes.Ok(Parsed(raw.trim))

val validate: Phase[String, Parsed, Validated] =
  (id, parsed, _, _, _) =>
    if parsed.content.nonEmpty then PhaseRes.Ok(Validated(parsed.content, true))
    else PhaseRes.Failure(Map(id -> Right("Validation failed")))

val transform: Phase[String, Validated, Transformed] =
  (_, validated, _, _, _) =>
    PhaseRes.Ok(Transformed(validated.content.toUpperCase))

val pipeline = RecPhase[String]
  .next(parse, "parse")
  .next(validate, "validate")
  .next(transform, "transform")

val endToEnd = PhaseRunner(pipeline, getLogger, listener)
assert(endToEnd("hello world") == PhaseRes.Ok(Transformed("HELLO WORLD")))
```

`PhaseRes.Failure` carries either thrown exceptions (`Left(Throwable)`) or string messages (`Right(String)`), allowing upstream callers to aggregate errors.

### Optional Phases with `nextOpt`

```scala
val enableValidation = true

val optionalPipeline = RecPhase[String]
  .next(parse, "parse")
  .nextOpt(if enableValidation then Some(validate) else None, "validate")
  .next(transform, "transform")
```

`nextOpt` skips the phase entirely when you pass `None`, keeping the type the same as the previous stage.

## 3. Requesting Dependencies (Intermediate)

Phases can ask for more IDs and combine their results. The framework replays the pipeline for every requested dependency and gives you a `SortedMap` keyed by ID.

```scala
case class Enriched(content: String, deps: Map[String, Parsed])

val enrich: Phase[String, Parsed, Enriched] =
  (id, parsed, getDeps, isCircular, logger) =>
    if isCircular then
      PhaseRes.Ok(Enriched(parsed.content + "-circular", Map.empty))
    else
      val requested = SortedSet("dep-1", "dep-2")
      getDeps(requested).map { resolved =>
        // resolved : SortedMap[String, Enriched]
        val deps = resolved.view.mapValues { parsedAgain =>
          Parsed(parsedAgain.content)
        }.toMap
        Enriched(parsed.content, deps)
      }

val dependencyPipeline = RecPhase[String]
  .next(parse, "parse")
  .next(enrich, "enrich")

val dependencyRunner = PhaseRunner(dependencyPipeline, getLogger, listener)
val enriched = dependencyRunner("root")
```

`PhaseRunner` automatically emits `PhaseListener.Blocked` events while dependency IDs are being resolved.

## 4. Error Handling & Ignored Results (Intermediate)

Wrap risky logic with `PhaseRes.attempt` to convert exceptions into structured failures.

```scala
val riskyPhase: Phase[String, String, String] =
  (id, value, _, _, logger) =>
    PhaseRes.attempt(id, logger, {
      if value.contains("boom") then throw RuntimeException("kaboom")
      PhaseRes.Ok(value.reverse)
    })

val riskyPipeline = RecPhase[String].next(riskyPhase, "risky")

assert(PhaseRunner(riskyPipeline, getLogger, listener)("nope") == PhaseRes.Ok("epon"))
assert(PhaseRunner(riskyPipeline, getLogger, listener)("boom!").isInstanceOf[PhaseRes.Failure[?, ?]])
```

Skip downstream work by returning `PhaseRes.Ignore()`:

```scala
val featureFlagged: Phase[String, String, String] =
  (id, value, _, _, _) =>
    if value.startsWith("skip") then PhaseRes.Ignore()
    else PhaseRes.Ok(value)
```

When a phase returns `Ignore`, remaining phases are not executed and listeners receive an `Ignored` event.

## 5. Observability with Listeners (Intermediate)

Implement `PhaseListener` to react to lifecycle events.

```scala
class PrintingListener extends PhaseListener[String] {
  override def on(phaseName: String, id: String, event: PhaseListener.Event[String]): Unit =
    println(s"[$phaseName][$id] -> $event")
}

val observedRunner = PhaseRunner(pipeline, getLogger, PrintingListener())
observedRunner("demo-id")
```

Events:
- `Started` – phase execution kicked off.
- `Blocked` – waiting on dependencies.
- `Success` – phase completed successfully.
- `Failure` – phase returned or threw an error.
- `Ignored` – phase skipped itself.

Use listeners for metrics, tracing, or progress bars (see `www/test/src/www/phases/PhaseIntegrationTests.scala`).

## 6. Leveraging Caching (Intermediate)

Each `Next` phase has its own `PhaseCache`. The framework caches `(id, isCircular)` results so repeated runs reuse previous work.

```scala
var parseCount = 0

val countingParse: Phase[String, String, Parsed] =
  (_, raw, _, _, _) =>
    parseCount += 1
    PhaseRes.Ok(Parsed(raw))

val cachedPipeline = RecPhase[String]
  .next(countingParse, "parse")

val cachedRunner = PhaseRunner(cachedPipeline, getLogger, listener)
assert(cachedRunner("id-1").isInstanceOf[PhaseRes.Ok[?, ?]])
assert(cachedRunner("id-1").isInstanceOf[PhaseRes.Ok[?, ?]])
assert(parseCount == 1) // second run hits the cache
```

When a phase requests dependencies, their results get cached separately under their own IDs.

## 7. Advanced Control (Advanced)

### Circular Dependency Awareness

`PhaseRunner` tracks a circuit breaker. When the pipeline revisits an ID already on the call stack, `isCircular` flips to `true`.

```scala
val circularAware: Phase[String, String, String] =
  (id, value, _, isCircular, logger) =>
    if isCircular then
      logger.warn(s"Circular dependency detected for $id")
      PhaseRes.Ok(value + "-circular")
    else
      PhaseRes.Ok(value + "-normal")

val circularPipeline = RecPhase[String].next(circularAware, "circular-aware")

// Simulate a circular call by seeding the breaker list
val res = PhaseRunner.go(circularPipeline, "node", List("node"), getLogger, listener)
assert(res == PhaseRes.Ok("node-circular"))
```

### Custom Logger Per ID

Swap `getLogger` for a registry that scopes output per phase or entity (see `www/src/www/logging/LogRegistry.scala`):

```scala
val store = new java.io.StringWriter
val pattern = www.logging.Pattern.default
val base = www.logging.Logger.toWriter(store, pattern)

val registry = www.logging.LogRegistry[String, String, java.io.StringWriter](
  outer = base.void,
  grouper = identity,
  subLogger = _ => www.logging.Logger.toWriter(new java.io.StringWriter, pattern)
)

def contextualLogger(id: String): Logger[Unit] = registry.get(id)
```

### Mixing Result Types

Phases can change both the identifier type and the payload type as you go. Just keep providing the required `Formatter` and `Ordering` instances for the current ID type.

```scala
case class RecordId(idx: Int)
case class Raw(value: String)
case class Processed(value: String)

given Formatter[RecordId] = id => Formatter(id.idx)
given Ordering[RecordId] = Ordering.by(_.idx)

def getRecordLogger(id: RecordId): Logger[Unit] = baseLogger

val phase = RecPhase[RecordId]
  .next((id, raw: RecordId, _, _, _) => PhaseRes.Ok(Raw(s"payload-${id.idx}")), "load")
  .next((_, r: Raw, _, _, _) => PhaseRes.Ok(Processed(r.value.reverse)), "process")

val recordRunner = PhaseRunner(phase, getRecordLogger, PhaseListener.NoListener)
assert(recordRunner(RecordId(1)) == PhaseRes.Ok(Processed("1-daolyap")))
```

## 8. Testing Strategies (Advanced)

### Unit Tests with Stubbed Dependencies

```scala
import utest._

object UppercasePhaseTests extends TestSuite {
  val phase: Phase[String, String, String] =
    (_, value, _, _, _) => PhaseRes.Ok(value.toUpperCase)

  val fakeGetDeps: GetDeps[String, String] = _ => PhaseRes.Ok(SortedMap.empty)

  def tests = Tests {
    test("uppercases inputs") {
      val res = phase("id", "hello", fakeGetDeps, isCircular = false, Logger.DevNull)
      assert(res == PhaseRes.Ok("HELLO"))
    }
  }
}
```

### Integration Tests with `PhaseRunner`

```scala
object PipelineIntegrationTests extends TestSuite {
  val listener = new PhaseListener[String] {
    val events = scala.collection.mutable.ArrayBuffer.empty[PhaseListener.Event[String]]
    override def on(name: String, id: String, event: PhaseListener.Event[String]): Unit =
      events += event
  }

  val runner = PhaseRunner(pipeline, getLogger, listener)

  def tests = Tests {
    test("success path") {
      val res = runner("integration")
      assert(res.isInstanceOf[PhaseRes.Ok[?, ?]])
      assert(listener.events.exists(_.isInstanceOf[PhaseListener.Success[String]]))
    }
  }
}
```

Run `./mill www.test` to execute both unit and integration suites.

## 9. Where to Go Next

- Browse `www/src/www/phases/PhaseRunner.scala` for the orchestration internals.
- Check `www/test/src/www/phases/DocumentationExamplesTests.scala` for additional end-to-end examples.
- Extend `Main.scala` to wire your pipeline into an application entry point.
- Add documentation or tests as you specialise the framework for your domain.

Happy phasing!
