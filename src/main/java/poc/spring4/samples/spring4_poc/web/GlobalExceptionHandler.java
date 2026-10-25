package poc.spring4.samples.spring4_poc.web;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Map;

/**
 * Routes any uncaught controller exception to:
 *   1. The ERRORS logger (which logback-spring.xml ships to a dedicated error log file).
 *   2. A dedicated micrometer counter {@code http.server.errors} tagged by exception class
 *      and request URI — queryable via /actuator/prometheus.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger ERRORS = LoggerFactory.getLogger("ERRORS");

    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException ex, HttpServletRequest req) {
        recordError(ex, req, HttpStatus.NOT_FOUND);
        return build(HttpStatus.NOT_FOUND, "not_found", "No handler for " + req.getRequestURI());
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<Map<String, Object>> handleErrorResponse(ErrorResponseException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        recordError(ex, req, status);
        return build(status, status.getReasonPhrase().toLowerCase().replace(' ', '_'), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception ex, HttpServletRequest req) {
        recordError(ex, req, HttpStatus.INTERNAL_SERVER_ERROR);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error",
                ex.getMessage() == null ? "unexpected error" : ex.getMessage());
    }

    private void recordError(Exception ex, HttpServletRequest req, HttpStatus status) {
        Counter.builder("http.server.errors")
                .description("Count of controller exceptions routed through the global handler")
                .tag("exception", ex.getClass().getSimpleName())
                .tag("uri", req.getRequestURI())
                .tag("method", req.getMethod())
                .tag("status", String.valueOf(status.value()))
                .register(meterRegistry)
                .increment();

        if (status.is5xxServerError()) {
            ERRORS.error("server error uri={} method={} exception={} message={}",
                    req.getRequestURI(), req.getMethod(), ex.getClass().getName(), ex.getMessage(), ex);
        } else {
            ERRORS.warn("client error uri={} method={} status={} exception={} message={}",
                    req.getRequestURI(), req.getMethod(), status.value(),
                    ex.getClass().getName(), ex.getMessage());
        }
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", code,
                "message", message,
                "timestamp", Instant.now().toString()
        ));
    }
}
