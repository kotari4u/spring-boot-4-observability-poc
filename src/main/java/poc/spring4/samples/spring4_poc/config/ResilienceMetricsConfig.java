package poc.spring4.samples.spring4_poc.config;

import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds Resilience4j retry & circuit-breaker gauges/counters to the Micrometer registry so
 * they appear in /actuator/prometheus. The resilience4j-spring-boot3 auto-config does this
 * on Boot 3 via the main auto-config imports file, but on Spring Boot 4 the binding is
 * not picking up — explicit registration keeps metrics guaranteed.
 */
@Configuration
public class ResilienceMetricsConfig {

    @Bean
    public TaggedRetryMetrics retryMetricsBinder(RetryRegistry registry, MeterRegistry meterRegistry) {
        TaggedRetryMetrics metrics = TaggedRetryMetrics.ofRetryRegistry(registry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }

    @Bean
    public TaggedCircuitBreakerMetrics circuitBreakerMetricsBinder(CircuitBreakerRegistry registry,
                                                                   MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics metrics = TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }
}
