package com.example.learnhub.echo;

import com.example.learnhub.common.correlation.CorrelationIds;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The placeholder service that proves registration, central configuration and gateway routing work.
 *
 * <p>Exactly two endpoints, and no more: one public and one protected. The protected one exists only
 * so the gateway's "protected by default" behaviour can be demonstrated before authentication is
 * built; this service performs no authentication itself, so called directly it answers the same way.
 */
@RestController
@RequestMapping("/api/v1/echo")
public class EchoController {

    private static final Logger log = LoggerFactory.getLogger(EchoController.class);

    private final EchoProperties properties;
    private final String instanceId;
    private final int port;

    public EchoController(EchoProperties properties,
            @Value("${eureka.instance.instance-id:${spring.application.name:echo-service}}") String instanceId,
            @Value("${server.port:9099}") int port) {
        this.properties = properties;
        this.instanceId = instanceId;
        this.port = port;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return respond("ping");
    }

    @GetMapping("/secure/ping")
    public Map<String, Object> securePing() {
        return respond("secure ping");
    }

    private Map<String, Object> respond(String what) {
        String correlationId = MDC.get(CorrelationIds.MDC_KEY);
        // The correlation ID is in MDC, so structured logging adds it to this line automatically.
        log.info("echo {} answered by {}", what, instanceId);

        return Map.of(
                "instanceId", instanceId,
                "port", port,
                "correlationId", correlationId == null ? "" : correlationId,
                "greeting", properties.getGreeting());
    }
}
