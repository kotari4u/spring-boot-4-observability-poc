package poc.spring4.samples.spring4_poc.filter;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Emits one structured log line per request with method, URI, status, client IP,
 * user agent, content type, and wall-clock duration. Also seeds MDC with a correlation
 * id so downstream logs can be joined on it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger ACCESS = LoggerFactory.getLogger("ACCESS");
    private static final String CORRELATION_ID = "correlationId";
    private static final String CORRELATION_HEADER = "X-Request-Id";

    /**
     * Required so this filter runs again on the async dispatch for reactive return types,
     * otherwise the response status captured below is always the pre-dispatch default (200).
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private static final String START_NS_ATTR = RequestLoggingFilter.class.getName() + ".startNs";
    private static final String CORRELATION_ATTR = RequestLoggingFilter.class.getName() + ".cid";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean isInitialDispatch = request.getDispatcherType() == DispatcherType.REQUEST;
        String correlationId;
        long startNs;
        if (isInitialDispatch) {
            correlationId = resolveCorrelationId(request);
            startNs = System.nanoTime();
            request.setAttribute(CORRELATION_ATTR, correlationId);
            request.setAttribute(START_NS_ATTR, startNs);
            response.setHeader(CORRELATION_HEADER, correlationId);
        } else {
            correlationId = (String) request.getAttribute(CORRELATION_ATTR);
            Long savedStart = (Long) request.getAttribute(START_NS_ATTR);
            startNs = savedStart == null ? System.nanoTime() : savedStart;
        }
        MDC.put(CORRELATION_ID, correlationId == null ? "-" : correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Defer logging on the initial dispatch if the controller returned a reactive
            // type — the async dispatch will run this filter again with the final status.
            boolean willContinueAsync = isInitialDispatch && request.isAsyncStarted();
            if (!willContinueAsync) {
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
                ACCESS.info("method={} uri={} status={} clientIp={} userAgent=\"{}\" contentType={} durationMs={}",
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus(),
                        resolveClientIp(request),
                        nullSafe(request.getHeader("User-Agent")),
                        nullSafe(request.getContentType()),
                        elapsedMs);
            }
            MDC.remove(CORRELATION_ID);
        }
    }

    private String resolveCorrelationId(HttpServletRequest req) {
        String incoming = req.getHeader(CORRELATION_HEADER);
        return StringUtils.hasText(incoming) ? incoming : UUID.randomUUID().toString();
    }

    private String resolveClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            return xff.split(",")[0].trim();
        }
        String realIp = req.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp;
        }
        return req.getRemoteAddr();
    }

    private String nullSafe(String s) {
        return StringUtils.hasText(s) ? s : "-";
    }
}
