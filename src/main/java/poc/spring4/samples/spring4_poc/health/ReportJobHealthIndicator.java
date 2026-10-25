package poc.spring4.samples.spring4_poc.health;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Surfaces the state of the "report" scheduled job on {@code /actuator/health}.
 *
 * Reads the {@code job.report} timer from the Micrometer registry (populated by the
 * {@code @Observed} annotation in SampleJobs) and derives:
 *   - UP      — the job has run and the error rate is tolerable (≤ threshold)
 *   - DOWN    — too many failures relative to successes
 *   - UNKNOWN — the job has never run (metric not present yet)
 *
 * Bean name {@code reportJob} (Spring strips the "HealthIndicator" suffix) becomes the
 * key under {@code /actuator/health}'s {@code components} map:
 *
 *   {
 *     "status": "UP",
 *     "components": {
 *       "reportJob": {
 *         "status": "UP",
 *         "details": { ... numbers below ... }
 *       }
 *     }
 *   }
 *
 * Benefit over just reading /actuator/metrics/job.report directly:
 *   - A single health call gives you a verdict + supporting numbers in one JSON blob.
 *   - K8s readiness/liveness probes already consume /actuator/health; now a persistently
 *     broken job can flip the pod unhealthy without wiring Prometheus Alertmanager.
 */
@Component("reportJob")
public class ReportJobHealthIndicator implements HealthIndicator {

    private static final String METRIC_NAME = "job.report";
    private static final double ERROR_RATE_THRESHOLD = 0.5;

    private final MeterRegistry meterRegistry;

    public ReportJobHealthIndicator(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Health health() {
        Collection<Timer> timers = meterRegistry.find(METRIC_NAME).timers();

        if (timers.isEmpty()) {
            return Health.unknown()
                    .withDetail("metric", METRIC_NAME)
                    .withDetail("message", "job has not run yet")
                    .build();
        }

        long successCount = sumCount(timers, true);
        long errorCount = sumCount(timers, false);
        long total = successCount + errorCount;
        double errorRate = total == 0 ? 0.0 : (double) errorCount / total;

        double meanMs = timers.stream()
                .mapToDouble(t -> t.mean(TimeUnit.MILLISECONDS))
                .average()
                .orElse(0.0);

        double maxMs = timers.stream()
                .mapToDouble(t -> t.max(TimeUnit.MILLISECONDS))
                .max()
                .orElse(0.0);

        Health.Builder builder = errorRate > ERROR_RATE_THRESHOLD
                ? Health.down()
                : Health.up();

        return builder
                .withDetail("metric", METRIC_NAME)
                .withDetail("totalExecutions", total)
                .withDetail("successCount", successCount)
                .withDetail("errorCount", errorCount)
                .withDetail("errorRate", round(errorRate))
                .withDetail("errorRateThreshold", ERROR_RATE_THRESHOLD)
                .withDetail("meanDurationMs", round(meanMs))
                .withDetail("maxDurationMs", round(maxMs))
                .build();
    }

    private long sumCount(Collection<Timer> timers, boolean successOnly) {
        return timers.stream()
                .filter(t -> {
                    String errorTag = t.getId().getTag("error");
                    boolean isSuccess = errorTag == null || "none".equals(errorTag);
                    return successOnly == isSuccess;
                })
                .mapToLong(Timer::count)
                .sum();
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
