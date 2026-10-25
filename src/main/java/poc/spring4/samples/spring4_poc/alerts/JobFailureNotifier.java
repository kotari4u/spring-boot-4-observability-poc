package poc.spring4.samples.spring4_poc.alerts;

import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dispatches a failure report over HTTP whenever a scheduled task throws.
 *
 * Design:
 *   - {@code @Async} — the scheduler thread must not block waiting for the alerting
 *     endpoint; the method returns immediately and runs on the shared task executor.
 *   - {@code @Retry} — the alerting endpoint itself may be flaky; two retries keep a
 *     transient 503 from losing the alert.
 *   - Fallback — if retries exhaust, log a WARN instead of propagating; the main app
 *     stays healthy even when the alerting backend is down.
 *   - URL via config — set {@code app.alerts.job-failure-url} empty to disable alerts
 *     entirely (useful in dev/test).
 */
@Service
public class JobFailureNotifier {

    private static final Logger log = LoggerFactory.getLogger(JobFailureNotifier.class);

    private final RestClient restClient;
    private final String notificationUrl;
    private final String serviceName;

    public JobFailureNotifier(
            RestClient.Builder builder,
            @Value("${app.alerts.job-failure-url:}") String notificationUrl,
            @Value("${spring.application.name}") String serviceName) {
        this.restClient = builder.build();
        this.notificationUrl = notificationUrl;
        this.serviceName = serviceName;
    }

    @Async
    @Retry(name = "jobFailureAlert", fallbackMethod = "onDispatchFailure")
    public void dispatch(Throwable ex) {
        if (!StringUtils.hasText(notificationUrl)) {
            log.debug("job-failure alerts disabled (app.alerts.job-failure-url is empty)");
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("service", serviceName);
        payload.put("job", extractJobName(ex));
        payload.put("exception", ex.getClass().getName());
        payload.put("message", ex.getMessage() == null ? "" : ex.getMessage());
        payload.put("stackTrace", stackTraceAsString(ex));
        payload.put("timestamp", Instant.now().toString());

        restClient.post()
                .uri(notificationUrl)
                .body(payload)
                .retrieve()
                .toBodilessEntity();

        log.info("job failure alert dispatched job={}", payload.get("job"));
    }

    @SuppressWarnings("unused")
    private void onDispatchFailure(Throwable triggeringException, Throwable alertingException) {
        log.warn("could not dispatch job-failure alert for {} — alerting endpoint error: {}",
                triggeringException.getClass().getSimpleName(),
                alertingException.toString());
    }

    /**
     * Walks the stack trace from the top and returns the first user frame that is NOT a
     * lambda, NOT the generic {@code withJobContext} wrapper, and NOT in a framework
     * package. For a Spring {@code @Scheduled} method that threw, this is the annotated
     * method itself (e.g. {@code SampleJobs#cleanup}).
     */
    private String extractJobName(Throwable ex) {
        for (StackTraceElement frame : ex.getStackTrace()) {
            String cls = frame.getClassName();
            String method = frame.getMethodName();
            if (cls.startsWith("java.") || cls.startsWith("jdk.")
                    || cls.startsWith("org.springframework.") || cls.startsWith("org.aspectj.")
                    || cls.contains("$$SpringCGLIB$$") || cls.contains("$$EnhancerBySpringCGLIB$$")) {
                continue;
            }
            if (method.contains("$") || "withJobContext".equals(method)) {
                continue;
            }
            return cls.substring(cls.lastIndexOf('.') + 1) + "#" + method;
        }
        return "unknown";
    }

    private String stackTraceAsString(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
