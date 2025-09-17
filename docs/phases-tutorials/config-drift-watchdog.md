# Configuration Drift Watchdog

Detect and summarize configuration drift across services. This example leans on caching (each service is analyzed once per run), optional phases, and `PhaseRes.Failure` to flag unexpected states.

## Why Phases Help Here

- **Fan-out with caching**: Services often depend on shared configs—if one service references another, `PhaseRunner` reuses cached results.
- **Optional enforcement**: Toggle expensive or noisy checks with `.nextOpt` instead of branching inside your business logic.
- **Structured failures**: Capture missing baselines as typed `Failure`s that downstream automation can inspect.

```
serviceId → pullCurrentConfig → compareWithBaseline → renderAlert → triggerPagerDuty?
```

## 1. Environment Setup

```scala
package www.examples

import www.logging.{Formatter, Logger, Pattern}
import www.phases.*
import scala.collection.immutable.{SortedMap, SortedSet}

// Framework givens
given Formatter[String] = Formatter.StringFormatter
given Ordering[String]  = Ordering.String

def getLogger(id: String): Logger[Unit] =
  www.logging.appendable(System.out, Pattern.default).void.withContext("service", id)

val listener: PhaseListener[String] = (phase, id, event) =>
  println(s"[$phase] $id :: $event")

// Simulated data stores
val baselineConfigs: Map[String, Map[String, String]] = Map(
  "billing" -> Map("retries" -> "3", "region" -> "us-east-1"),
  "search"  -> Map("retries" -> "2", "region" -> "eu-central-1", "batch" -> "disabled")
)

val liveConfigs: Map[String, Map[String, String]] = Map(
  "billing" -> Map("retries" -> "5", "region" -> "us-east-1"),
  "search"  -> Map("retries" -> "2", "region" -> "eu-central-1", "batch" -> "enabled"),
  "mail"    -> Map("region" -> "us-west-2")
)
```

## 2. Data Models

```scala
case class ServiceConfig(id: String, values: Map[String, String])
case class DriftReport(id: String, missingKeys: List[String], changed: Map[String, (String, String)])
case class Alert(id: String, severity: String, message: String)
```

## 3. Phase Implementations

```scala
val pullCurrentConfig: Phase[String, String, ServiceConfig] =
  (id, _, _, _, logger) =>
    liveConfigs.get(id) match {
      case Some(values) => PhaseRes.Ok(ServiceConfig(id, values))
      case None =>
        logger.warn(s"No runtime config found for $id")
        PhaseRes.Ignore()
    }

val compareWithBaseline: Phase[String, ServiceConfig, DriftReport] =
  (id, current, _, _, logger) =>
    baselineConfigs.get(id) match {
      case None =>
        logger.error(s"Missing baseline for $id")
        PhaseRes.Failure(Map(id -> Right("Baseline configuration missing")))
      case Some(baseline) =>
        val missingKeys = baseline.keySet.diff(current.values.keySet).toList.sorted
        val changed = baseline.flatMap { case (key, expected) =>
          current.values.get(key) match {
            case Some(actual) if actual != expected => Some(key -> (expected, actual))
            case _                                  => None
          }
        }
        logger.info(s"Detected ${missingKeys.size} missing keys and ${changed.size} changes")
        PhaseRes.Ok(DriftReport(id, missingKeys, changed))
    }

val renderAlert: Phase[String, DriftReport, Alert] =
  (id, report, _, _, _) => {
    val severity =
      if report.missingKeys.nonEmpty || report.changed.nonEmpty then "high"
      else "info"
    val summary =
      s"${report.id}: missing=${report.missingKeys.mkString(",")}, changed=${report.changed.keys.mkString(",")}".stripSuffix(",")
    PhaseRes.Ok(Alert(id, severity, summary))
  }

val triggerPagerDuty: Phase[String, Alert, Alert] =
  (id, alert, _, _, logger) => {
    if alert.severity == "high" then
      logger.warn(s"Triggering PagerDuty for $id -> ${alert.message}")
    PhaseRes.Ok(alert)
  }
```

## 4. Compose With Optional Steps

```scala
val enablePagerDuty = true

val watchdog: RecPhase[String, Alert] =
  RecPhase[String]
    .next(pullCurrentConfig, "pull-current-config")
    .next(compareWithBaseline, "compare-with-baseline")
    .next(renderAlert, "render-alert")
    .nextOpt(if enablePagerDuty then Some(triggerPagerDuty) else None, "trigger-pagerduty")

val runner = PhaseRunner(watchdog, getLogger, listener)

@main def auditConfigs(): Unit = {
  List("billing", "search", "mail", "unknown").foreach { serviceId =>
    runner(serviceId) match {
      case PhaseRes.Ok(alert) =>
        println(s"${alert.id}: ${alert.severity.toUpperCase} -> ${alert.message}")
      case PhaseRes.Ignore() =>
        println(s"Skipped $serviceId (no live config)")
      case PhaseRes.Failure(errors) =>
        println(s"Failed $serviceId -> $errors")
    }
  }
}
```

## 5. Ideas To Extend

- Wire a `PhaseListener` that records durations and publishes them to your metrics stack.
- Replace the fake maps with loaders that query Git, S3, or Vault. `PhaseRes.attempt` helps wrap side effects safely.
- Expand `compareWithBaseline` to request dependencies such as shared platform configs via `getDeps`, letting the cache prevent duplicate work.

The phases framework keeps the watchdog deterministic and debuggable while staying flexible enough to grow with real-world integrations.
