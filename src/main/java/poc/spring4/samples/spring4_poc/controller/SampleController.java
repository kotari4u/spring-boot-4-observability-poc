package poc.spring4.samples.spring4_poc.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import poc.spring4.samples.spring4_poc.service.FlakyService;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SampleController {

    private static final Logger log = LoggerFactory.getLogger(SampleController.class);

    private final FlakyService flakyService;

    public SampleController(FlakyService flakyService) {
        this.flakyService = flakyService;
    }

    @GetMapping("/ping")
    public Mono<ResponseEntity<Map<String, Object>>> ping(HttpServletRequest request) {
        return Mono.fromSupplier(() -> {
            log.info("ping received from={} contentType={}", request.getRemoteAddr(), request.getContentType());
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "timestamp", Instant.now().toString()
            ));
        });
    }

    @GetMapping("/flaky")
    public Mono<ResponseEntity<Map<String, Object>>> flaky() {
        return flakyService.callFlakyExternal().map(ResponseEntity::ok);
    }

    @GetMapping("/flaky-exhausted")
    public Mono<ResponseEntity<Map<String, Object>>> flakyExhausted() {
        return flakyService.callAlwaysFailing().map(ResponseEntity::ok);
    }

    @GetMapping("/observed")
    public ResponseEntity<Map<String, Object>> observed() {
        return ResponseEntity.ok(flakyService.doObservedWork());
    }

    /**
     * Mock sink for the scheduled-job failure alerts. Just logs the payload and returns 202.
     * In production, {@code app.alerts.job-failure-url} would point at PagerDuty / Opsgenie /
     * a Slack webhook / your internal alerting service.
     */
    @PostMapping("/mock/alerts")
    public ResponseEntity<Void> receiveAlert(@RequestBody Map<String, Object> payload) {
        log.warn("ALERT RECEIVED job={} exception={} message={}",
                payload.get("job"), payload.get("exception"), payload.get("message"));
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/boom")
    public Mono<ResponseEntity<Map<String, Object>>> boom() {
        return Mono.fromSupplier(() -> {
            throw new IllegalStateException("intentional failure for metrics demo");
        });
    }

    @PostMapping("/echo")
    public Mono<ResponseEntity<Map<String, Object>>> echo(@RequestBody(required = false) Map<String, Object> body,
                                                          HttpServletRequest request) {
        return Mono.fromSupplier(() -> {
            log.info("echo received from={} payloadSize={}", request.getRemoteAddr(),
                    body == null ? 0 : body.size());
            return ResponseEntity.ok(Map.of(
                    "received", body == null ? Map.of() : body,
                    "timestamp", Instant.now().toString()
            ));
        });
    }
}
