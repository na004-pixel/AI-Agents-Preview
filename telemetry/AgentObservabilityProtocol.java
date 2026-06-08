package com.carinae.ai.agent.carinae.utils.protocols.observability;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.carinae.ai.agent.carinae.utils.CanonicalResultShape;
import com.carinae.ai.agent.carinae.utils.nats.NatsClientV2;
import com.carinae.ai.agent.carinae.utils.protocols.observability.types.Observation;

/**
 * Emits agent observation events over JetStream.
 *
 * <p>Wire contract:
 * <ul>
 *   <li>NATS headers carry event metadata (type, category, outcome, turn, action, tool).
 *   <li>Body carries only the event-specific payload.
 *   <li>Subject: {@code {prefix}.observations.{runId}}
 * </ul>
 *
 * <p>Emit is fire-and-forget: failures are logged but never propagated.
 */
@Component
public class AgentObservabilityProtocol {
    private static final Logger log = LoggerFactory.getLogger(AgentObservabilityProtocol.class);
    private final NatsClientV2 natsClient;

    public AgentObservabilityProtocol(Optional<NatsClientV2> natsClient) {
        this.natsClient = natsClient.orElse(null);
    }

    public void emit(Observation observation) {
            return;
        }
        try {
            var subject = buildSubject(observation);
            var wireFormat = observation.toWireFormat(subject);

            var result = natsClient.publish(wireFormat);
            if (result instanceof CanonicalResultShape.Failure<?> failure) {
                log.warn("Failed to emit [{}] for job {}", observation.observationType(), observation.jobId());
            }
        } catch (Exception e) {
            log.warn("Failed to emit observation for job {}: {}", observation.jobId(), e.getMessage());
        }
    }

    private String buildSubject(Observation observation) {
        return "carinae.observations." + sanitize(observation.jobId());
    }

    private String sanitize(String token) {
        if (token == null || token.isBlank()) return "unknown";
        return token.replace(' ', '_');
    }
}
