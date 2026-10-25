package poc.spring4.samples.spring4_poc.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import poc.spring4.samples.spring4_poc.alerts.JobFailureNotifier;

/**
 * Installs a global {@code ErrorHandler} on the Spring Boot auto-configured task
 * scheduler. Every exception thrown by any {@code @Scheduled} method is routed through
 * {@link #handle(JobFailureNotifier, Throwable)} without each job having to opt in.
 *
 * Why {@code ThreadPoolTaskSchedulerCustomizer} instead of defining a scheduler bean:
 *   - Pool size, thread name prefix, and shutdown behavior from spring.task.scheduling.*
 *     continue to apply (we only *tweak* the auto-configured instance, not replace it).
 *   - Any future addition in that property namespace is picked up automatically.
 */
@Configuration
public class SchedulingErrorHandlerConfig {

    private static final Logger log = LoggerFactory.getLogger("SCHED-ERR");

    @Bean
    public ThreadPoolTaskSchedulerCustomizer schedulerErrorHandler(JobFailureNotifier notifier) {
        return scheduler -> scheduler.setErrorHandler(ex -> handle(notifier, ex));
    }

    private void handle(JobFailureNotifier notifier, Throwable ex) {
        // 1. Log — preserves Spring's default visibility; writes to the normal console/MDC.
        log.error("scheduled task failed: {}", ex.toString(), ex);

        // 2. Dispatch the external alert. @Async + @Retry on the notifier handle both
        //    offloading and resilience; this call returns immediately.
        try {
            notifier.dispatch(ex);
        } catch (Exception alertEx) {
            log.warn("alert dispatch submission failed", alertEx);
        }
    }
}
