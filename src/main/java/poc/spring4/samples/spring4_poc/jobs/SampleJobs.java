package poc.spring4.samples.spring4_poc.jobs;

import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled jobs with full observability wiring:
 *
 *   - {@code @Scheduled}  — triggers the job on the configured interval/cron.
 *   - {@code @Observed}   — records a Micrometer timer per invocation. The {@code error}
 *                           tag is set automatically from any exception that escapes the
 *                           method, so success-vs-failure is a Prometheus label.
 *   - {@link #withJobContext(String, Runnable)} — wraps the body to:
 *       (1) seed a per-run correlation id in MDC so log lines are joinable;
 *       (2) time the run and log success at INFO or failure at ERROR;
 *       (3) rethrow failures so the @Observed aspect tags the metric and so Spring's
 *           default scheduled-task error handler logs it as well.
 *
 * Intervals/crons are externalized to {@code app.jobs.*} so you can tune per environment
 * (even disable a job in prod by setting its cron to {@code "-"}).
 */
@Component
public class SampleJobs {

    private static final Logger log = LoggerFactory.getLogger(SampleJobs.class);
    private static final Logger ERRORS = LoggerFactory.getLogger("ERRORS");
    private static final String CORRELATION_ID = "correlationId";

    private final AtomicInteger cleanupInvocations = new AtomicInteger();

    /**
     * Heartbeat — always succeeds. Fires fixedDelay after the previous run completes, so
     * long-running work can never overlap itself.
     */
    @Observed(name = "job.heartbeat", lowCardinalityKeyValues = {"job", "heartbeat"})
    @Scheduled(
            fixedDelayString = "${app.jobs.heartbeat.fixed-delay:10s}",
            initialDelayString = "${app.jobs.heartbeat.initial-delay:2s}"
    )
    public void heartbeat() {
        withJobContext("heartbeat", () -> log.info("heartbeat tick"));
    }

    /**
     * Cleanup — fails on every 3rd invocation to demonstrate the failure branch of the
     * metric ({@code error=<ExceptionClass>}) and the routing to the dedicated errors log.
     */
    @Observed(name = "job.cleanup", lowCardinalityKeyValues = {"job", "cleanup"})
    @Scheduled(
            fixedDelayString = "${app.jobs.cleanup.fixed-delay:15s}",
            initialDelayString = "${app.jobs.cleanup.initial-delay:5s}"
    )
    public void cleanup() {
        withJobContext("cleanup", () -> {
            int n = cleanupInvocations.incrementAndGet();
            log.info("cleanup starting invocation={}", n);
            if (n % 3 == 0) {
                throw new IllegalStateException("cleanup failed on invocation " + n);
            }
            log.info("cleanup finished invocation={}", n);
        });
    }

    /**
     * Report — cron-driven example. Default cron fires every 30 seconds. Set
     * {@code app.jobs.report.cron: "-"} to disable the job entirely without a code change.
     */
    @Observed(name = "job.report", lowCardinalityKeyValues = {"job", "report"})
    @Scheduled(cron = "${app.jobs.report.cron:*/30 * * * * *}")
    public void report() {
        withJobContext("report", () -> log.info("report generated"));
    }

    private void withJobContext(String jobName, Runnable work) {
        String correlationId = "job-" + jobName + "-" + System.nanoTime();
        MDC.put(CORRELATION_ID, correlationId);
        long startNs = System.nanoTime();
        try {
            work.run();
            long ms = (System.nanoTime() - startNs) / 1_000_000;
            log.info("job success name={} durationMs={}", jobName, ms);
        } catch (RuntimeException ex) {
            long ms = (System.nanoTime() - startNs) / 1_000_000;
            ERRORS.error("job failed name={} durationMs={} exception={} message={}",
                    jobName, ms, ex.getClass().getName(), ex.getMessage(), ex);
            // Rethrow so (1) @Observed captures error=<ExceptionClass>,
            //          (2) Spring's TaskUtils error handler also logs it.
            throw ex;
        } finally {
            MDC.remove(CORRELATION_ID);
        }
    }
}
