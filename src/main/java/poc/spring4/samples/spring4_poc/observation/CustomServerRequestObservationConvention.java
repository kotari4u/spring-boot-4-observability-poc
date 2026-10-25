package poc.spring4.samples.spring4_poc.observation;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Adds high-cardinality attributes (client IP, user agent, request content type) to the
 * server request observation. These flow to traces/logs but are NOT emitted as metric
 * dimensions, keeping the time-series database from blowing up on unique IPs.
 */
@Component
public class CustomServerRequestObservationConvention extends DefaultServerRequestObservationConvention {

    private static final String UNKNOWN = "unknown";

    @Override
    public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
        KeyValues values = super.getHighCardinalityKeyValues(context);
        HttpServletRequest req = context.getCarrier();
        if (req == null) {
            return values;
        }
        return values
                .and(KeyValue.of("client.ip", resolveClientIp(req)))
                .and(KeyValue.of("user.agent", nullSafe(req.getHeader("User-Agent"))))
                .and(KeyValue.of("request.content_type", nullSafe(req.getContentType())));
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
        return nullSafe(req.getRemoteAddr());
    }

    private String nullSafe(String s) {
        return StringUtils.hasText(s) ? s : UNKNOWN;
    }
}
