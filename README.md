# Spring Boot 4 — Observability POC

A production-shaped example of the full observability stack on **Spring Boot 4.1.2**:
reactive REST endpoints, Micrometer metrics with histograms, structured logs with
correlation IDs, Resilience4j retry, scheduled jobs with success/failure tracking,
custom health indicators, and a global failure-alert hook.

Every pattern here is designed around a single question: *when something goes wrong
at 3 AM, do you have the data to find it in under a minute?*

---

## Table of contents

- [What's inside](#whats-inside)
- [Prerequisites](#prerequisites)
- [Run it](#run-it)
- [Endpoints](#endpoints)
- [Metrics quick tour](#metrics-quick-tour)
- [Logs](#logs)
- [Resilience4j retry](#resilience4j-retry)
- [Scheduled jobs](#scheduled-jobs)
- [Scheduled-job failure alerts](#scheduled-job-failure-alerts)
- [Health indicator for a scheduled job](#health-indicator-for-a-scheduled-job)
- [Project layout](#project-layout)
- [Configuration reference](#configuration-reference)

---

## What's inside

| Capability | How it's wired | File |
|---|---|---|
| Reactive REST endpoints (`Mono<ResponseEntity<...>>`) on Spring MVC | `reactor-core` on classpath, Spring MVC's `ReactiveAdapterRegistry` | [SampleController.java](src/main/java/poc/spring4/samples/spring4_poc/controller/SampleController.java) |
| Per-request correlation IDs (MDC) propagated across Reactor schedulers | `OncePerRequestFilter` + Micrometer Context Propagation + `Hooks.enableAutomaticContextPropagation()` | [RequestLoggingFilter.java](src/main/java/poc/spring4/samples/spring4_poc/filter/RequestLoggingFilter.java), [ReactorMdcPropagationConfig.java](src/main/java/poc/spring4/samples/spring4_poc/config/ReactorMdcPropagationConfig.java) |
| HTTP metrics with histograms + percentiles + SLO buckets | Micrometer `distribution.percentiles-histogram` + custom `ServerRequestObservationConvention` | [CustomServerRequestObservationConvention.java](src/main/java/poc/spring4/samples/spring4_poc/observation/CustomServerRequestObservationConvention.java), [application.yaml](src/main/resources/application.yaml) |
| Structured access + error logs with dedicated appenders | Logback rolling files per logger name | [logback-spring.xml](src/main/resources/logback-spring.xml) |
| `@Retry` with success-after-retry + exhaustion + fallback | Resilience4j Spring Boot 3 starter + resilience4j-reactor | [FlakyService.java](src/main/java/poc/spring4/samples/spring4_poc/service/FlakyService.java) |
| Retry event logging (every attempt → log line) | `RegistryEventConsumer<Retry>` bean | [RetryEventLoggingConfig.java](src/main/java/poc/spring4/samples/spring4_poc/config/RetryEventLoggingConfig.java) |
| `@Observed` → Micrometer timer + trace span auto-tagged with success/error | Spring Boot's `ObservedAspect` (enabled via `management.observations.annotations.enabled`) | [FlakyService.java](src/main/java/poc/spring4/samples/spring4_poc/service/FlakyService.java), [SampleJobs.java](src/main/java/poc/spring4/samples/spring4_poc/jobs/SampleJobs.java) |
| `@Scheduled` jobs with thread pool + graceful shutdown + externalized cron | `spring.task.scheduling.*` properties | [SampleJobs.java](src/main/java/poc/spring4/samples/spring4_poc/jobs/SampleJobs.java), [application.yaml](src/main/resources/application.yaml) |
| Custom `HealthIndicator` reading job metrics from Micrometer | `MeterRegistry` injected, derives UP/DOWN from error rate | [ReportJobHealthIndicator.java](src/main/java/poc/spring4/samples/spring4_poc/health/ReportJobHealthIndicator.java) |
| Global failure alert — one `ErrorHandler` for all `@Scheduled` jobs | `ThreadPoolTaskSchedulerCustomizer` + `@Async` + `@Retry` on an HTTP POST | [SchedulingErrorHandlerConfig.java](src/main/java/poc/spring4/samples/spring4_poc/config/SchedulingErrorHandlerConfig.java), [JobFailureNotifier.java](src/main/java/poc/spring4/samples/spring4_poc/alerts/JobFailureNotifier.java) |

---

## Prerequisites

- **Java 22** (configured in [build.gradle](build.gradle) toolchain). JDK 21 would require only a one-line change but isn't installed on the dev machine this was built on.
- Gradle wrapper is checked in — no local Gradle install needed.

```bash
# verify your Java
java -version

# or point Gradle at a specific JDK
export JAVA_HOME=$(/usr/libexec/java_home -v 22)
```

---

## Run it

```bash
./gradlew bootRun
```

App listens on `:8080`. First boot takes ~3 seconds. You'll see:
- Three `@Scheduled` jobs start firing (heartbeat every 10s, cleanup every 15s, report cron every 30s)
- `logs/access.log` and `logs/errors.log` get created on the first request/failure

Stop with Ctrl-C. Graceful shutdown waits up to 30s for in-flight scheduled jobs to finish.

---

## Endpoints

### Application endpoints

| Method | Path | What it does |
|---|---|---|
| GET | `/api/ping` | Reactive `Mono` endpoint — logs the request and returns `{status: ok}` |
| POST | `/api/echo` | Reactive endpoint — echoes the posted JSON body |
| GET | `/api/flaky` | Calls a service decorated with `@Retry` that fails 2 of every 3 invocations — HTTP response is always 200 because retry recovers |
| GET | `/api/flaky-exhausted` | Service that always fails → retry exhausts after 3 attempts → fallback method returns 200 with `fallback-after-exhaustion` payload |
| GET | `/api/observed` | Synchronous method with `@Observed` — emits a Micrometer timer `flaky.sync.operation` |
| GET | `/api/boom` | Throws `IllegalStateException` — routes to `GlobalExceptionHandler` → `errors.log` + `http.server.errors` counter |
| POST | `/api/mock/alerts` | Mock sink for scheduled-job failure notifications (demos the alert flow) |

### Actuator endpoints

| Path | Content |
|---|---|
| `/actuator/health` | Composite health including custom `reportJob` indicator |
| `/actuator/health/reportJob` | Just the report-job health signal |
| `/actuator/prometheus` | Scrape endpoint — HTTP timings, retry stats, scheduled-task stats, JVM/pool metrics |
| `/actuator/metrics` | Lists all registered meter names |
| `/actuator/metrics/{name}` | Point lookup, e.g. `/actuator/metrics/job.cleanup` |
| `/actuator/retries` | Registered Resilience4j retry instances |
| `/actuator/retryevents` | Rolling buffer of RETRY / SUCCESS / ERROR events |
| `/actuator/circuitbreakers` | Registered circuit breakers (none active in this POC) |
| `/actuator/mappings` | All `@RequestMapping` routes |
| `/actuator/loggers` | Inspect & change log levels at runtime |
| `/actuator/httpexchanges` | Ring buffer of recent HTTP requests |

---

## Metrics quick tour

### HTTP request timing (auto-instrumented)

```promql
# p95 latency per endpoint
histogram_quantile(0.95,
  sum by (uri, le) (rate(http_server_requests_seconds_bucket[5m])))

# failure rate per endpoint
sum by (uri) (rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m]))
```

### Retry outcomes

```promql
# retries recovered successfully
rate(resilience4j_retry_calls_total{kind="successful_with_retry"}[5m])

# retries exhausted → fallback invoked
rate(resilience4j_retry_calls_total{kind="failed_with_retry"}[5m])
```

### Scheduled job success vs failure

```promql
# auto-tagged by Spring Boot's TaskScheduler observations
rate(tasks_scheduled_execution_seconds_count{outcome="ERROR"}[5m])

# or the custom @Observed metric
rate(job_cleanup_seconds_count{error!="none"}[5m])
```

### Scheduler thread pool health

```promql
# backlog — if this climbs, add scheduler threads
executor_queued_tasks{name="taskScheduler"}
```

---

## Logs

Three independent log streams, all tagged with the per-request/job correlation ID:

| Logger | Appender | File | Purpose |
|---|---|---|---|
| `ACCESS` | `ACCESS_FILE` + console | `logs/access.log` | One line per HTTP request (method, URI, status, IP, UA, content-type, duration) |
| `ERRORS` | `ERROR_FILE` + console | `logs/errors.log` | Server errors from `GlobalExceptionHandler` + scheduled-job failures (one line + stack trace per incident) |
| `RETRY` | console | — | One line per retry attempt / success / exhaustion |
| `SCHED-ERR` | console | — | One line per scheduled task that threw |

All lines carry `[cid=...]` in the pattern so one `grep <correlation-id>` reconstructs a full trace, including threads crossed via Reactor's `parallel-N` scheduler.

Sample for one failing cleanup job:
```
19:21:45.057 [sched-4]            INFO  SampleJobs   - cleanup starting invocation=3
19:21:45.063 [sched-4]            ERROR ERRORS       - job failed name=cleanup durationMs=0 ...
19:21:45.063 [sched-4]            ERROR SCHED-ERR    - scheduled task failed: ...
19:21:45.183 [http-nio-8080-exec] WARN  Sample...    - ALERT RECEIVED job=SampleJobs#cleanup ...
19:21:45.203 [task-1]             INFO  JobFailure.. - job failure alert dispatched job=SampleJobs#cleanup
```

---

## Resilience4j retry

Two retry instances configured in [application.yaml](src/main/resources/application.yaml):

- `flaky` — 3 attempts, 150ms base wait, exponential backoff ×2. Used by [`/api/flaky`](src/main/java/poc/spring4/samples/spring4_poc/service/FlakyService.java). Succeeds after retries.
- `alwaysFail` — same policy. Used by [`/api/flaky-exhausted`](src/main/java/poc/spring4/samples/spring4_poc/service/FlakyService.java). Always fails → fallback method invoked.
- `jobFailureAlert` — used internally by [JobFailureNotifier](src/main/java/poc/spring4/samples/spring4_poc/alerts/JobFailureNotifier.java) when posting failure alerts.

Only transient-ish exceptions trigger a retry; `IllegalArgumentException` is in `ignore-exceptions` so bad input fails fast.

### What you can observe

```bash
# three HTTP calls → six fail attempts → three fallback success responses
for i in 1 2 3; do curl -s http://localhost:8080/api/flaky; done

# the retry event stream
curl http://localhost:8080/actuator/retryevents | jq

# retry counters
curl -s http://localhost:8080/actuator/prometheus | grep resilience4j_retry
```

---

## Scheduled jobs

Three jobs demonstrate the three scheduling patterns. All are externalized to `app.jobs.*`
and use the `fixedDelayString` form (never `fixedRate`) to prevent self-overlap.

| Job | Trigger | Behavior |
|---|---|---|
| `heartbeat` | `fixedDelay = 10s` | Always succeeds |
| `cleanup` | `fixedDelay = 15s` | Fails every 3rd invocation |
| `report` | `cron = */30 * * * * *` | Always succeeds |

The scheduler itself:
- Pool size 4 (so a slow job can't block a fast one)
- Thread name prefix `sched-` for easy stack-trace reading
- `await-termination: true` with 30s cap on SIGTERM — in-flight jobs finish before shutdown

Each job method is wrapped in `withJobContext(name, Runnable)` which:
1. Seeds a per-run correlation ID in MDC
2. Logs success + duration
3. On exception: logs to the `ERRORS` logger then rethrows (so `@Observed` records `error=<ExceptionClass>` and the global `ErrorHandler` fires)

---

## Scheduled-job failure alerts

**Pattern:** install one `ErrorHandler` on the scheduler and every `@Scheduled` method
that throws routes through it, with no per-job code changes.

```
@Scheduled method throws
    │
    ▼
Spring TaskScheduler catches it
    │
    ▼
ErrorHandler (installed via ThreadPoolTaskSchedulerCustomizer)
    │
    ├──► log via SCHED-ERR logger (preserves default visibility)
    │
    └──► JobFailureNotifier.dispatch(ex)   [@Async → runs on task-1 thread]
             │
             ▼
         @Retry("jobFailureAlert")
             │
             ▼
         RestClient POST → ${app.alerts.job-failure-url}
```

Payload:
```json
{
  "service":    "spring4-poc",
  "job":        "SampleJobs#cleanup",
  "exception":  "java.lang.IllegalStateException",
  "message":    "cleanup failed on invocation 3",
  "stackTrace": "...",
  "timestamp":  "2026-10-25T23:21:45Z"
}
```

Set `app.alerts.job-failure-url: ""` to disable alerts entirely (dev/test).
In production, point it at PagerDuty / Opsgenie / Slack webhook / your internal alerting service.

---

## Health indicator for a scheduled job

[ReportJobHealthIndicator](src/main/java/poc/spring4/samples/spring4_poc/health/ReportJobHealthIndicator.java) reads the `job.report` timer from the Micrometer registry and surfaces it at `/actuator/health/reportJob`:

```json
{
  "status": "UP",
  "details": {
    "metric": "job.report",
    "totalExecutions": 2,
    "successCount": 2,
    "errorCount": 0,
    "errorRate": 0.0,
    "errorRateThreshold": 0.5,
    "meanDurationMs": 0.843,
    "maxDurationMs": 1.118
  }
}
```

Status flips to `DOWN` if the error rate exceeds 50%, which Kubernetes readiness probes consume automatically — a persistently broken scheduled job can mark the pod unhealthy with zero extra wiring.

---

## Project layout

```
src/main/java/poc/spring4/samples/spring4_poc/
├── Spring4PocApplication.java          # @SpringBootApplication + @EnableScheduling + @EnableAsync
├── alerts/
│   └── JobFailureNotifier.java         # @Async + @Retry — HTTP POST on scheduled-job failure
├── config/
│   ├── ReactorMdcPropagationConfig.java      # MDC survives Reactor scheduler hops
│   ├── ResilienceMetricsConfig.java          # Binds TaggedRetryMetrics to MeterRegistry
│   ├── RetryEventLoggingConfig.java          # Logs every retry attempt/success/exhaustion
│   └── SchedulingErrorHandlerConfig.java     # Global ErrorHandler for @Scheduled failures
├── controller/
│   └── SampleController.java           # /api/* endpoints
├── filter/
│   └── RequestLoggingFilter.java       # Per-request access log + correlation ID
├── health/
│   └── ReportJobHealthIndicator.java   # Custom HealthIndicator reading from MeterRegistry
├── jobs/
│   └── SampleJobs.java                 # @Scheduled jobs with @Observed
├── observation/
│   └── CustomServerRequestObservationConvention.java  # IP/UA/content-type as high-card attrs
├── service/
│   └── FlakyService.java               # @Retry + @Observed demos
└── web/
    └── GlobalExceptionHandler.java     # @RestControllerAdvice routes failures to errors.log

src/main/resources/
├── application.yaml                    # Fully commented — see "Configuration reference" below
└── logback-spring.xml                  # Dedicated appenders for ACCESS and ERRORS loggers
```

---

## Configuration reference

Every entry in [application.yaml](src/main/resources/application.yaml) has an inline *What / Benefit* comment explaining what the setting does and why we chose it. The grouped sections:

- `spring.application` + `spring.task.scheduling` — service identity + scheduler thread pool / shutdown
- `server` — Tomcat connector + disabling the native access log
- `management.endpoints` — actuator exposure list
- `management.metrics` — histograms, percentiles, SLO buckets for HTTP timings
- `management.prometheus` — scrape endpoint and step interval
- `management.observations.annotations.enabled` — turns on `@Observed`
- `resilience4j.retry` — shared defaults + per-instance overrides (`flaky`, `alwaysFail`, `jobFailureAlert`)
- `app.alerts.job-failure-url` — where scheduled-job failure POSTs are sent
- `app.jobs.*` — externalized cron/interval per scheduled job
- `logging` — level per logger + console pattern (includes `[cid=...]` for correlation)

---

## License

POC code — use as a reference.
