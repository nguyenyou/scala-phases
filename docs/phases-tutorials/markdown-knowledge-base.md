# Markdown Knowledge Base Compiler

Turn a folder of Markdown notes into rendered pages while resolving cross-document links. This tutorial demonstrates dependency fan-out (`getDeps`), conditional skips with `Ignore`, and lightweight logging hooks.

## Scenario Overview

- **Input ID**: each Markdown file path (e.g. `docs/onboarding.md`).
- **Phase 1 – Load**: fetch the raw Markdown by ID.
- **Phase 2 – Parse**: pull out front matter and collect outgoing wiki-style links.
- **Phase 3 – Resolve**: call back into the pipeline to ensure linked documents are parsed.
- **Phase 4 – Render**: emit a trivial HTML payload plus a backlink index.

```
(ID, file system) → loadMarkdown → parseStructure → resolveLinks → renderHtml
```

## 1. Playground Setup

```scala
package www.examples

import www.logging.{Formatter, Logger, Pattern}
import www.phases.*
import scala.collection.immutable.{SortedMap, SortedSet}

// Givens required by the framework
given Formatter[String] = Formatter.StringFormatter
given Ordering[String]  = Ordering.String

def getLogger(id: String): Logger[Unit] =
  www.logging.appendable(System.out, Pattern.default).void.withContext("doc", id)

val listener: PhaseListener[String] = (phase, id, event) =>
  println(s"[$phase] $id => $event")

// Fake file system populated with Markdown content
val markdownFiles: Map[String, String] = Map(
  "guides/onboarding.md" ->
    """|---
        |title: Onboarding
        |---
        |
        |Welcome! See [[guides/checklist.md]] for next steps.
        |""".stripMargin,
  "guides/checklist.md" ->
    """|---
        |title: Checklist
        |---
        |
        |1. Request access
        |2. Review [[policies/security.md]]
        |""".stripMargin,
  "policies/security.md" ->
    """|---
        |title: Security
        |---
        |
        |Always lock your screen.
        |""".stripMargin,
)
```

## 2. Data Structures

```scala
case class ParsedDoc(id: String, title: String, body: String, links: List[String])
case class ResolvedDoc(doc: ParsedDoc, resolvedLinks: SortedMap[String, ParsedDoc])
case class RenderedPage(id: String, html: String)
```

## 3. Phase Implementations

```scala
val loadMarkdown: Phase[String, String, String] =
  (id, _, _, _, logger) =>
    markdownFiles.get(id) match {
      case Some(raw) => PhaseRes.Ok(raw)
      case None =>
        logger.warn(s"Missing Markdown file: $id")
        PhaseRes.Ignore()
    }

val parseStructure: Phase[String, String, ParsedDoc] =
  (id, raw, _, _, _) => {
    val lines       = raw.linesIterator.toList
    val title       = lines.dropWhile(!_.startsWith("title:")).headOption.map(_.drop("title:".length).trim).getOrElse(id)
    val contentBody = lines.dropWhile(_.nonEmpty).drop(1).mkString("\n")
    val links = "\\[\\[(.*?)\\]\\]".r.findAllMatchIn(contentBody).map(m => m.group(1).trim).toList
    PhaseRes.Ok(ParsedDoc(id, title, contentBody, links))
  }

val resolveLinks: Phase[String, ParsedDoc, ResolvedDoc] =
  (id, doc, getDeps, isCircular, logger) => {
    if doc.links.isEmpty then
      PhaseRes.Ok(ResolvedDoc(doc, SortedMap.empty))
    else if isCircular then {
      logger.warn(s"Circular link detected for $id -> ${doc.links.mkString(", ")}")
      PhaseRes.Ok(ResolvedDoc(doc, SortedMap.empty))
    } else {
      val requested = SortedSet.from(doc.links.filter(markdownFiles.contains))
      if requested.isEmpty then
        PhaseRes.Ok(ResolvedDoc(doc, SortedMap.empty))
      else
        getDeps(requested).map(deps => ResolvedDoc(doc, deps))
    }
  }

val renderHtml: Phase[String, ResolvedDoc, RenderedPage] =
  (id, resolved, _, _, logger) => {
    val heading    = s"<h1>${resolved.doc.title}</h1>"
    val paragraphs = resolved.doc.body.split("\n\n").map(p => s"<p>$p</p>").mkString
    val backlinks =
      if resolved.resolvedLinks.isEmpty then ""
      else {
        val items = resolved.resolvedLinks.values.map(doc => s"<li><a href='${doc.id}.html'>${doc.title}</a></li>").mkString
        s"<aside><h2>Outgoing Links</h2><ul>$items</ul></aside>"
      }

    val html = heading + paragraphs + backlinks
    logger.info(s"Rendered HTML for $id with ${resolved.resolvedLinks.size} links")
    PhaseRes.Ok(RenderedPage(id, html))
  }
```

## 4. Assemble And Execute

```scala
val knowledgeBase: RecPhase[String, RenderedPage] =
  RecPhase[String]
    .next(loadMarkdown, "load-markdown")
    .next(parseStructure, "parse-structure")
    .next(resolveLinks, "resolve-links")
    .next(renderHtml, "render-html")

val runner = PhaseRunner(knowledgeBase, getLogger, listener)

@main def buildKnowledgeBase(): Unit = {
  List("guides/onboarding.md", "guides/checklist.md", "policies/security.md", "missing.md").foreach { id =>
    runner(id) match {
      case PhaseRes.Ok(page) =>
        println("----")
        println(s"Rendered ${page.id}.html\n${page.html}")
      case PhaseRes.Ignore() =>
        println(s"Skipped $id (file not found)")
      case PhaseRes.Failure(errors) =>
        println(s"Failed $id -> $errors")
    }
  }
}
```

## 5. Experiment Further

- Add a fifth phase that writes `RenderedPage` content to disk or an in-memory cache.
- Extend `resolveLinks` to build backlinks by requesting IDs discovered in other documents.
- Attach a custom listener that records duration per phase and exports metrics for dashboards.

This recipe shows how the phases framework naturally models document graphs and lets you resolve dependencies without re-implementing orchestration logic.
