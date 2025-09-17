# Phases Framework Tutorial

Learn how to compose, observe, and test pipelines built with `www.phases`. Every section scales from copy-paste snippets to full recipes you can run inside this repository.

- Beginner: Create your first phase, chain transforms, and execute pipelines.
- Intermediate: Request dependencies, branch conditionally, cache results, and reason about errors.
- Advanced: Build multi-stage applications, wire custom listeners/loggers, and test at different layers.

> All examples assume the code lives somewhere under `www/src/www` (e.g. `Tutorial.scala`). Use `./mill www.compile` or the REPL (`./mill -i repl`) to try them out.

## 0. Prerequisites & Scaffolding

Place this shared setup at the top of your scratch file. It brings the framework into scope and defines helper givens required by `PhaseRunner`.

```scala
import www.logging.{Formatter, Logger}
import www.phases._
import scala.collection.immutable.{SortedMap, SortedSet}

// String IDs work out of the box
given Formatter[String] = Formatter.StringFormatter
given Ordering[String]  = Ordering.String

// Basic logger and listener for examples
val baseLogger: Logger[Unit] = Logger.DevNull
def getLogger(id: String): Logger[Unit] = baseLogger
def listener: PhaseListener[String] = PhaseListener.NoListener
```

Need a runnable entry point? Wrap the examples in an `@main` method:

```scala
@main def runTutorial(): Unit = {
  // paste sections below inside this body as you go
}
```

## 1. Your First Phase (Beginner)

Phases are tiny functions describing how to transform data from one stage to the next.

```scala
val uppercasePhase: Phase[String, String, String] =
  (id, value, _ /* getDeps */, _ /* isCircular */, _ /* logger */) =>
    PhaseRes.Ok(value.toUpperCase)

val basicPipeline: RecPhase[String, String] =
  RecPhase[String].next(uppercasePhase, "uppercase")

val runner = PhaseRunner(basicPipeline, getLogger, listener)

assert(runner("hello") == PhaseRes.Ok("HELLO"))
```

Key points:
- `RecPhase[String]` seeds the pipeline with an `Initial` identity phase.
- `.next` hangs a new transformation onto the chain.
- `PhaseRunner` executes the pipeline and returns a `PhaseRes` (`Ok`, `Failure`, or `Ignore`).

## 2. Building Linear Pipelines (Beginner)

Model a text-processing workflow with three stages: parse, validate, and transform.

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

val textPipeline = RecPhase[String]
  .next(parse, "parse")
  .next(validate, "validate")
  .next(transform, "transform")

val textRunner = PhaseRunner(textPipeline, getLogger, listener)
assert(textRunner("  hello world  ") == PhaseRes.Ok(Transformed("HELLO WORLD")))
assert(textRunner("   ").isInstanceOf[PhaseRes.Failure[?, ?]])
```

### Optional Phases with `nextOpt`

```scala
val enableValidation = false

val optionalPipeline = RecPhase[String]
  .next(parse, "parse")
  .nextOpt(if enableValidation then Some(validate) else None, "validate")
  .next(transform, "transform")

val optionalRunner = PhaseRunner(optionalPipeline, getLogger, listener)
assert(optionalRunner("skip validation") == PhaseRes.Ok(Transformed("SKIP VALIDATION")))
```

## 3. Branching and Fallbacks (Intermediate)

Use `PhaseRes.Ignore` to short-circuit downstream phases or provide fallback behaviour.

```scala
val featurePhase: Phase[String, String, String] =
  (id, value, _, _, _) =>
    if value.startsWith("skip") then PhaseRes.Ignore() else PhaseRes.Ok(value + "-feature")

val fallbackPhase: Phase[String, String, String] =
  (_, value, _, _, _) => PhaseRes.Ok(value + "-fallback")

val fallbackPipeline = RecPhase[String]
  .next(featurePhase, "feature")
  .next(fallbackPhase, "fallback")

val fallbackRunner = PhaseRunner(fallbackPipeline, getLogger, listener)
assert(fallbackRunner("skip-me") == PhaseRes.Ignore())
assert(fallbackRunner("run-me") == PhaseRes.Ok("run-me-feature-fallback"))
```

Combine ignore with success/failure logic to implement feature flags or conditional computation.

## 4. Working with Dependencies (Intermediate)

Phases can request additional IDs and automatically receive their results.

```scala
case class Enriched(content: String, deps: Map[String, Parsed])

def enrich: Phase[String, Parsed, Enriched] =
  (id, parsed, getDeps, isCircular, logger) =>
    if isCircular then
      logger.warn(s"Circular dependency for $id")
      PhaseRes.Ok(Enriched(parsed.content + "-circular", Map.empty))
    else
      val requested = SortedSet("article:intro", "article:references")
      getDeps(requested).map { resolved =>
        val deps = resolved.view.mapValues(pd => Parsed(pd.content)).toMap
        Enriched(parsed.content, deps)
      }

val dependencyPipeline = RecPhase[String]
  .next(parse, "parse")
  .next(enrich, "enrich")

val dependencyRunner = PhaseRunner(dependencyPipeline, getLogger, listener)
val enriched = dependencyRunner("article:body")
println(enriched)
```

The runner replays the pipeline for every requested ID, caching along the way. Listeners receive a `Blocked` event while dependencies are resolving.

### Dependency-Driven Flow Control

You can branch based on dependency results:

```scala
val reviewPhase: Phase[String, Enriched, String] =
  (id, enriched, _, _, _) =>
    if enriched.deps.isEmpty then PhaseRes.Failure(Map(id -> Right("Missing references")))
    else PhaseRes.Ok(s"${enriched.content}:${enriched.deps.keys.mkString(",")}")

val reviewPipeline = dependencyPipeline.next(reviewPhase, "review")
println(PhaseRunner(reviewPipeline, getLogger, listener)("article:body"))
```

## 5. Recipe: Markdown Publishing Pipeline (Intermediate)

Model a documentation build where articles depend on shared snippets.

```scala
case class RawDoc(id: String, content: String)
case class RenderedDoc(id: String, html: String, references: List[String])

val docs = Map(
  "intro" -> RawDoc("intro", "# Intro\nSee {{common}}."),
  "common" -> RawDoc("common", "*Shared block*"),
  "advanced" -> RawDoc("advanced", "# Advanced\nRefs {{common}} {{intro}}")
)

val loadDoc: Phase[String, String, RawDoc] =
  (id, _, _, _, _) => docs.get(id) match
    case Some(doc) => PhaseRes.Ok(doc)
    case None      => PhaseRes.Failure(Map(id -> Right("Document not found")))

val parseReferences: Phase[String, RawDoc, (RawDoc, SortedSet[String])] =
  (_, doc, _, _, _) =>
    val refs = "\\{\\{([^}]+)\\}\\}".r.findAllMatchIn(doc.content).map(_.group(1)).to(SortedSet)
    PhaseRes.Ok(doc -> refs)

val hydrate: Phase[String, (RawDoc, SortedSet[String]), RenderedDoc] =
  (id, (doc, refs), getDeps, _, _) =>
    getDeps(refs).map { resolved =>
      val html = refs.foldLeft(doc.content.replaceAll("\\{\\{([^}]+)\\}\\}", "<span data-ref='$1'></span>")) {
        case (acc, ref) => acc.replace(s"<span data-ref='$ref'></span>", resolved(ref).html)
      }
      RenderedDoc(id, html, refs.toList)
    }

val publishPipeline = RecPhase[String]
  .next(loadDoc, "load")
  .next(parseReferences, "references")
  .next(hydrate, "hydrate")

val publish = PhaseRunner(publishPipeline, getLogger, listener)
println(publish("advanced"))
```

## 6. Recipe: Incremental Build Graph (Advanced)

Treat IDs as file paths and compute derived artefacts with caching.

```scala
import java.nio.file.{Files, Paths}

case class FileSnapshot(path: String, hash: Int)
case class Bundle(files: List[FileSnapshot])

val listDeps: Map[String, SortedSet[String]] = Map(
  "index" -> SortedSet("header", "footer"),
  "header" -> SortedSet.empty,
  "footer" -> SortedSet("copyright"),
  "copyright" -> SortedSet.empty
)

val loadFile: Phase[String, String, FileSnapshot] =
  (id, _, _, _, _) =>
    val path = Paths.get(s"/tmp/$id.txt")
    if Files.exists(path) then
      val content = Files.readString(path)
      PhaseRes.Ok(FileSnapshot(id, content.hashCode))
    else
      PhaseRes.Failure(Map(id -> Right("Missing file")))

val aggregate: Phase[String, FileSnapshot, Bundle] =
  (id, snapshot, getDeps, _, _) =>
    getDeps(listDeps.getOrElse(id, SortedSet.empty)).map { resolved =>
      Bundle(snapshot :: resolved.values.toList)
    }

val buildPipeline = RecPhase[String]
  .next(loadFile, "load")
  .next(aggregate, "aggregate")

val buildRunner = PhaseRunner(buildPipeline, getLogger, listener)
println(buildRunner("index"))
```

Each call caches `(id, isCircular)` pairs so re-running the pipeline after a no-op edit reuses earlier results.

## 7. Logging & Observability (Advanced)

### Custom Listeners

Collect timings for each phase:

```scala
class TimingListener extends PhaseListener[String] {
  private val started = scala.collection.mutable.Map.empty[(String, String), Long]
  val measurements = scala.collection.mutable.ArrayBuffer.empty[(String, String, Long)]

  override def on(phase: String, id: String, event: PhaseListener.Event[String]): Unit =
    event match
      case PhaseListener.Started(_) => started((phase, id)) = System.nanoTime()
      case PhaseListener.Success(_) | PhaseListener.Failure(_, _) =>
        val duration = System.nanoTime() - started.remove((phase, id)).getOrElse(0L)
        measurements += ((phase, id, duration))
      case _ => ()
}

val timingListener = new TimingListener
PhaseRunner(textPipeline, getLogger, timingListener)("hello")
println(timingListener.measurements)
```

### Structured Logging per ID

```scala
val buffer = new java.io.StringWriter
val pattern = www.logging.Pattern.default
val base = www.logging.writer(buffer, pattern)

def loggingGetLogger(id: String): Logger[Unit] =
  base.withContext("inputId", id).void

val loggingRunner = PhaseRunner(textPipeline, loggingGetLogger, listener)
loggingRunner("log-me")
println(buffer.toString)
```

Combine listeners and logging to integrate with metrics backends or tracing systems.

## 8. Caching in Practice (Advanced)

Observe cache hits by counting executions:

```scala
var loadCount = 0

val cachedLoad: Phase[String, String, Parsed] =
  (_, raw, _, _, _) =>
    loadCount += 1
    PhaseRes.Ok(Parsed(raw))

val cachedPipeline = RecPhase[String].next(cachedLoad, "cached-load")
val cachedRunner = PhaseRunner(cachedPipeline, getLogger, listener)

cachedRunner("same-id")
cachedRunner("same-id")
println(s"Phase executed $loadCount time(s)") // -> 1
```

Cache keys include the `isCircular` flag; requests triggered via circular detection are stored separately.

## 9. Error Handling Patterns (Advanced)

### Wrap Exceptions with `PhaseRes.attempt`

```scala
val riskyPhase: Phase[String, String, String] =
  (id, value, _, _, logger) =>
    PhaseRes.attempt(id, logger, {
      if value.contains("boom") then throw RuntimeException("kaboom")
      PhaseRes.Ok(value.reverse)
    })

val riskyRunner = PhaseRunner(RecPhase[String].next(riskyPhase, "risky"), getLogger, listener)
println(riskyRunner("boom"))
```

### Aggregate Multiple Failures

```scala
val collectErrors: Phase[String, String, String] =
  (id, value, _, _, _) =>
    PhaseRes.Failure(
      Map(
        id -> Right("Primary failure"),
        (id + "#dep") -> Left(new IllegalStateException("Secondary failure"))
      )
    )

println(PhaseRunner(RecPhase[String].next(collectErrors, "fail"), getLogger, listener)("id"))
```

Downstream code receives a `Map` keyed by failing IDs with either a captured throwable or message.

## 10. Testing Playbook (Advanced)

### Unit Testing a Single Phase

```scala
import utest._

object UppercasePhaseTests extends TestSuite {
  val phase: Phase[String, String, String] =
    (_, value, _, _, _) => PhaseRes.Ok(value.toUpperCase)

  val fakeGetDeps: GetDeps[String, String] = _ => PhaseRes.Ok(SortedMap.empty)

  def tests = Tests {
    test("uppercases input") {
      val output = phase("id", "hello", fakeGetDeps, isCircular = false, Logger.DevNull)
      assert(output == PhaseRes.Ok("HELLO"))
    }
  }
}
```

### Testing Dependency Handling

```scala
object DependencyPhaseTests extends TestSuite {
  val dependencyPhase: Phase[String, String, String] =
    (id, value, getDeps, _, _) =>
      getDeps(SortedSet("dep1", "dep2")).map { deps =>
        s"$value -> ${deps.keys.mkString("," )}"
      }

  def tests = Tests {
    test("requests dependencies") {
      val fakeDeps: GetDeps[String, String] = ids =>
        PhaseRes.Ok(SortedMap.from(ids.map(_ -> "result")))

      val res = dependencyPhase("root", "base", fakeDeps, isCircular = false, Logger.DevNull)
      assert(res == PhaseRes.Ok("base -> dep1,dep2"))
    }
  }
}
```

### Integration Tests

```scala
object PipelineIntegrationTests extends TestSuite {
  class RecordingListener extends PhaseListener[String] {
    val events = scala.collection.mutable.ListBuffer.empty[PhaseListener.Event[String]]
    override def on(phase: String, id: String, event: PhaseListener.Event[String]): Unit =
      events += event
  }

  val listener = new RecordingListener
  val runner = PhaseRunner(textPipeline, getLogger, listener)

  def tests = Tests {
    test("success path emits events") {
      val res = runner("Hello")
      assert(res.isInstanceOf[PhaseRes.Ok[?, ?]])
      assert(listener.events.exists(_.isInstanceOf[PhaseListener.Started[String]]))
      assert(listener.events.exists(_.isInstanceOf[PhaseListener.Success[String]]))
    }
  }
}
```

Run everything with `./mill www.test`.

## 11. Patterns & Tips

- **ID Types:** Switch to domain-specific IDs (e.g. `case class UserId(id: Long)`) as soon as you need type safety. Provide `Formatter` and `Ordering` instances.
- **Phase Naming:** Provide descriptive names in `.next`—they appear in logs and listener events.
- **Pure Functions:** Phases can stay pure by returning new data instead of mutating state. Side effects can be injected via the logger.
- **Memoisation:** The built-in cache reduces repeated work within a single run. If you need persistence between runs, wrap phase results in your own memo layer.
- **Circuit Breaker:** Custom logic for cycles goes inside the `isCircular` branch—either return fallback data or raise a failure.

## 12. Troubleshooting

- **Missing Formatter/Ordering:** Ensure `given Formatter[Id]` and `given Ordering[Id]` are in scope before creating the runner.
- **Unexpected `Ignore`:** Check earlier phases—once a phase returns `Ignore`, no later phases execute.
- **Silent Failures:** Attach a listener or custom logger to surface failures; `PhaseRes.Failure` carries all errors as a map.
- **Infinite Recursion:** Use `PhaseRunner.go(...)` to inspect the circuit breaker stack and confirm you are requesting the correct dependencies.
- **Testing:** Mirror the repository’s tests (`www/test/src/www/phases`) when adding new behaviour.

## 13. Next Steps

- Read `www/src/www/phases/PhaseRunner.scala` for orchestration internals.
- Explore `www/test/src/www/phases/DocumentationExamplesTests.scala` for more scenarios.
- Wire your own pipeline into `Main.scala` and surface results via logging or HTTP.
- Document domain-specific phases next to this tutorial to onboard teammates faster.

Happy phasing!
