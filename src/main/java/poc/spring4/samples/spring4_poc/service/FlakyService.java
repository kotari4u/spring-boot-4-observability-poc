package poc.spring4.samples.spring4_poc.service;

import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates a flaky downstream dependency so the @Retry annotation can be exercised.
 * Fails on 2 of every 3 invocations, so with max-attempts=3 the retry succeeds within
 * each HTTP request. Must live on a different bean than the caller — Spring's AOP
 * proxy only intercepts calls that cross a proxy boundary, so self-invocation would
 * silently bypass the annotation.
 */
@Service
public class FlakyService {

    private static final Logger log = LoggerFactory.getLogger(FlakyService.class);

    private final AtomicInteger invocations = new AtomicInteger();

    @Retry(name = "flaky", fallbackMethod = "flakyFallback")
    public Mono<Map<String, Object>> callFlakyExternal() {
        return Mono.fromCallable(() -> {
            int n = invocations.incrementAndGet();
            log.info("flaky external invocation={}", n);
            if (n % 3 != 0) {
                throw new IllegalStateException("transient failure invocation=" + n);
            }
            return Map.of("result", "ok", "invocation", n);
        });
    }

    @SuppressWarnings("unused")
    private Mono<Map<String, Object>> flakyFallback(Throwable ex) {
        log.warn("retry exhausted — falling back cause={}", ex.toString());
        return Mono.just(Map.of(
                "result", "fallback",
                "reason", ex.getClass().getSimpleName() + ": " + ex.getMessage()
        ));
    }

    /**
     * Always fails so the retry policy exhausts and the fallback path kicks in.
     * Useful for demoing the "retry exhausted" branch end-to-end.
     */
    @Retry(name = "alwaysFail", fallbackMethod = "alwaysFailFallback")
    public Mono<Map<String, Object>> callAlwaysFailing() {
        return Mono.fromCallable(() -> {
            log.info("alwaysFail attempt — about to throw");
            throw new IllegalStateException("downstream permanently unavailable");
        });
    }

    @SuppressWarnings("unused")
    private Mono<Map<String, Object>> alwaysFailFallback(Throwable ex) {
        log.warn("alwaysFail retry exhausted — serving fallback payload");
        return Mono.just(Map.of(
                "result", "fallback-after-exhaustion",
                "reason", ex.getClass().getSimpleName() + ": " + ex.getMessage()
        ));
    }

    /**
     * Demonstrates the Micrometer {@code @Observed} annotation.
     *
     * The ObservedAspect bean (auto-registered because
     * {@code management.observations.annotations.enabled=true}) wraps every call to
     * this method in a single Observation, which fans out to:
     *   - a Micrometer Timer named {@code flaky.sync.operation} with the low-cardinality
     *     tags listed below plus Spring Boot's standard {@code error} / {@code class} /
     *     {@code method} tags; exported to Prometheus as
     *     {@code flaky_sync_operation_seconds_{count,sum,max,bucket}}.
     *   - a trace span named {@code flaky-sync-demo} (visible if a tracing backend is wired).
     *
     * Why use it instead of recording a Timer manually:
     *   - One annotation replaces boilerplate try/finally + sample.stop() code.
     *   - Metric name + span name + tags stay in lockstep; no drift between telemetry types.
     *   - Exceptions thrown out of the method automatically tag the observation with
     *     {@code error=<ExceptionClass>}, no manual error reporting needed.
     *
     * Note: @Observed on a Mono-returning method closes the observation when the Mono is
     * RETURNED, not when it completes — so we keep this example synchronous. For reactive
     * timing use {@code Mono.name(...).tag(...).tap(Micrometer.observation(registry))}.
     */
    @Observed(
            name = "flaky.sync.operation",
            contextualName = "flaky-sync-demo",
            lowCardinalityKeyValues = {"component", "flaky-service", "operation", "demo"}
    )
    public Map<String, Object> doObservedWork() {
        log.info("executing @Observed sync operation");
        try {
            Thread.sleep(40);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Map.of("result", "ok", "work", "done");
    }
}
