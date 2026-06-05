package com.carinae.ai.agent.carinae.utils.protocols.observability;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.carinae.ai.agent.carinae.utils.CanonicalWireFormat;
import com.carinae.ai.agent.carinae.utils.nats.NatsClientV2;

@Component
public class JobsRuntimeObservabilityProtocol {
    private static final Logger log = LoggerFactory.getLogger(JobsRuntimeObservabilityProtocol.class);
    private static final String PROTOCOL = "jobs-runtime-observability";

    private final NatsClientV2 natsClient;

    public JobsRuntimeObservabilityProtocol(Optional<NatsClientV2> natsClient) {
        this.natsClient = natsClient.orElse(null);
    }

    public void emit(String eventType, String component, Map<String, String> metadata, Map<String, Object> body) {
            return;
        }

        String nodeId = "node-1"; // [Simplified] Node ID resolution
        
        var headers = new java.util.LinkedHashMap<String, String>();
        headers.put("carinae-message-type", eventType);
        headers.put("carinae-node-id", nodeId);
        headers.put("carinae-protocol", PROTOCOL);

        CanonicalWireFormat wireFormat = new CanonicalWireFormat(
                "carinae.jobs.runtime." + nodeId,
                headers,
                body != null ? Map.of("data", body) : Map.of()
        );

        try {
            natsClient.publish(wireFormat);
        } catch (Exception e) {
            log.warn("Failed to emit jobs runtime event");
        }
    }
}
