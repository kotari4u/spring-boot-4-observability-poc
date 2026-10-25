package poc.spring4.samples.spring4_poc.config;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import jakarta.annotation.PostConstruct;
import org.slf4j.MDC;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;

/**
 * Makes SLF4J MDC propagate across Reactor's scheduler hops. Without this, a retry that
 * executes on parallel-N loses the correlationId set by RequestLoggingFilter, so retry
 * logs can't be joined to the originating HTTP request.
 *
 * Mechanism:
 *   1. Register an MDC {@link ThreadLocalAccessor} with the Context Propagation registry
 *      so Reactor knows how to snapshot/restore MDC values across threads.
 *   2. Flip Reactor's global flag so every operator automatically captures and restores
 *      the thread-local context on scheduler boundaries.
 */
@Configuration
public class ReactorMdcPropagationConfig {

    static final String CORRELATION_KEY = "correlationId";

    @PostConstruct
    public void init() {
        ContextRegistry.getInstance().registerThreadLocalAccessor(new MdcCorrelationAccessor());
        Hooks.enableAutomaticContextPropagation();
    }

    private static final class MdcCorrelationAccessor implements ThreadLocalAccessor<String> {
        @Override
        public Object key() {
            return CORRELATION_KEY;
        }

        @Override
        public String getValue() {
            return MDC.get(CORRELATION_KEY);
        }

        @Override
        public void setValue(String value) {
            MDC.put(CORRELATION_KEY, value);
        }

        @Override
        public void setValue() {
            MDC.remove(CORRELATION_KEY);
        }
    }
}
