# TypeScript → Scala.js Stub Generator

Build a mini transpiler that reads TypeScript component definitions and emits Scala.js facade stubs. The real project would wire an actual parser and code generator, but this walkthrough focuses on how the phases framework keeps the pipeline modular, cacheable, and observable.

## Pipeline At A Glance

- **Phase 0 – Initial**: Seeds the pipeline with the input ID (a TypeScript module path).
- **Phase 1 – Parse**: Pretend to parse the TypeScript source into a simplified AST.
- **Phase 2 – Model**: Convert the AST into a Scala.js-friendly representation.
- **Phase 3 – Emit**: Render Scala.js facade code and metadata that downstream tooling can persist.

```
(ID, raw source) → parseTs → buildFacadeModel → emitScalaJs
```

## 1. Shared Setup

Drop this scaffold into a scratch file under `www/src/www` (e.g. `TsToScalaJs.scala`). It provides imports, givens, and a tiny registry of fake TypeScript sources.

```scala
package www.examples

import www.logging.{Formatter, Logger, Pattern}
import www.phases.*
import scala.collection.immutable.{SortedMap, SortedSet}

// Framework givens
given Formatter[String] = Formatter.StringFormatter
given Ordering[String]  = Ordering.String

// A lightweight console logger for demo output
val baseLogger: Logger[Unit] =
  www.logging.appendable(System.out, Pattern.default).void

def getLogger(id: String): Logger[Unit] =
  baseLogger.withContext("module", id)

def listener: PhaseListener[String] = (phaseName, id, event) =>
  println(s"[$phaseName] $id -> $event")

// Pretend TypeScript registry we will translate
val typeScriptSources: Map[String, String] = Map(
  "components/Button" ->
    """
      |export interface ButtonProps {
      |  label: string
      |  onClick?: () => void
      |}
      |
      |export function Button(props: ButtonProps): JSX.Element;
      |""".stripMargin,
  "components/Card" ->
    """
      |export interface CardProps {
      |  title: string
      |  children: React.ReactNode
      |}
      |
      |export function Card(props: CardProps): JSX.Element;
      |""".stripMargin,
)
```

## 2. Domain Types

Keep the types tiny but explicit so each phase has a well-defined input and output.

```scala
case class SimplifiedTsAst(name: String, exports: List[String])
case class ScalaFacade(name: String, methods: List[String])
case class ScalaJsArtifact(id: String, code: String, warnings: List[String])
```

## 3. Phase Implementations

Each `Phase` receives `(id, value, getDeps, isCircular, logger)` and returns a `PhaseRes`. Here we only use happy-path `Ok` values but you can extend them with `Failure` or `Ignore` to handle edge cases.

```scala
val parseTs: Phase[String, String, SimplifiedTsAst] =
  (id, _, _, _, logger) => {
    val raw = typeScriptSources.getOrElse(id, "")
    if raw.isEmpty then
      logger.warn(s"Missing TypeScript module: $id")
      PhaseRes.Ignore()
    else
      val exportedLines = raw.linesIterator.filter(_.startsWith("export")).toList
      val simplified    = SimplifiedTsAst(name = id.split('/').last, exports = exportedLines)
      PhaseRes.Ok(simplified)
  }

val buildFacadeModel: Phase[String, SimplifiedTsAst, ScalaFacade] =
  (id, ast, _, _, logger) => {
    val methods = ast.exports.collect {
      case line if line.contains("function") =>
        val signature = line.replace("export function", "def").stripSuffix(";")
        s"  @$"""JSName("${ast.name}")"""" + "\n  " + signature + " = ???"
    }
    if methods.isEmpty then
      logger.warn(s"No callable exports in $id; generating empty facade")
    PhaseRes.Ok(ScalaFacade(ast.name, methods))
  }

val emitScalaJs: Phase[String, ScalaFacade, ScalaJsArtifact] =
  (id, facade, _, _, logger) => {
    val header =
      s"""|package facade.${id.split('/').init.mkString('.')}
          |
          |import scala.scalajs.js
          |import scala.scalajs.js.annotation.JSName
          |
          |object ${facade.name} extends js.Object {
          |""".stripMargin
    val body =
      if facade.methods.nonEmpty then facade.methods.mkString("\n") else "  // TODO: add methods"
    val footer = "}\n"

    val warnings =
      if facade.methods.isEmpty then List(s"${facade.name} has no callable exports") else Nil

    val code = header + body + "\n" + footer
    logger.info(s"Generated ${facade.methods.size} facade methods for ${facade.name}")
    PhaseRes.Ok(ScalaJsArtifact(id, code, warnings))
  }
```

## 4. Compose and Run

Chain the phases with `RecPhase.next` calls, then feed module IDs to `PhaseRunner`.

```scala
val pipeline: RecPhase[String, ScalaJsArtifact] =
  RecPhase[String]
    .next(parseTs, "parse-ts")
    .next(buildFacadeModel, "build-facade-model")
    .next(emitScalaJs, "emit-scala-js")

val runner = PhaseRunner(pipeline, getLogger, listener)

@main def runTsToScalaJs(): Unit = {
  List("components/Button", "components/Card", "components/Missing").foreach { moduleId =>
    PhaseRunner(pipeline, getLogger, listener)(moduleId) match {
      case PhaseRes.Ok(artifact) =>
        println("----")
        println(s"Scala.js facade for ${artifact.id}\n${artifact.code}")
        artifact.warnings.foreach(w => println(s"Warning: $w"))
      case PhaseRes.Ignore() =>
        println(s"Skipped $moduleId (missing source)")
      case PhaseRes.Failure(errors) =>
        println(s"Failed $moduleId -> $errors")
    }
  }
}
```

Running `./mill www.compile` (or the file via the REPL) prints the emitted facade stubs and emits listener/ logger output showing how each phase progresses.

## 5. Extending The Recipe

- **Real parser**: Swap the fake `export` filter for a proper TypeScript parser and return richer AST nodes.
- **Dependency graph**: If modules import each other, use `getDeps` inside `parseTs` to request dependent IDs so you only parse each file once.
- **Artifact packaging**: Replace the simple `println` with a persistence phase that writes files or pushes them to an in-memory `PhaseCache` for incremental builds.

With the scaffolding above you can bolt in concrete TypeScript tooling while letting the phases framework handle ordering, caching, and observability.
