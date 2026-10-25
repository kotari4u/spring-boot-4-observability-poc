package poc.spring4.samples.spring4_poc.config;

import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hooks every Retry instance registered in the application context and emits a log line
 * on each retry attempt, final success, and final error (retries exhausted). Combined
 * with the MDC correlation id set by RequestLoggingFilter, each retry attempt is
 * attributable to a single inbound HTTP request.
 */
@Configuration
public class RetryEventLoggingConfig {

    private static final Logger log = LoggerFactory.getLogger("RETRY");

    @Bean
    public RegistryEventConsumer<Retry> appRetryEventLogger() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<Retry> event) {
                attachListeners(event.getAddedEntry());
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<Retry> event) {
                // no-op; retries live for the lifetime of the application
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<Retry> event) {
                attachListeners(event.getNewEntry());
            }
        };
    }

    private void attachListeners(Retry retry) {
        retry.getEventPublisher()
                .onRetry(e -> log.warn("retry name={} attempt={} waitMs={} cause={}: {}",
                        e.getName(),
                        e.getNumberOfRetryAttempts(),
                        e.getWaitInterval() == null ? 0 : e.getWaitInterval().toMillis(),
                        e.getLastThrowable().getClass().getSimpleName(),
                        e.getLastThrowable().getMessage()))
                .onSuccess(e -> log.info("retry succeeded name={} afterAttempts={}",
                        e.getName(),
                        e.getNumberOfRetryAttempts()))
                .onError(e -> log.error("retry exhausted name={} totalAttempts={} cause={}: {}",
                        e.getName(),
                        e.getNumberOfRetryAttempts(),
                        e.getLastThrowable().getClass().getSimpleName(),
                        e.getLastThrowable().getMessage()));
    }
}
