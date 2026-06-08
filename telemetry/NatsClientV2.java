package com.carinae.ai.agent.carinae.utils.nats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.carinae.ai.agent.carinae.tools.types.toolTransport.DomainError;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.ErrorKind;
import com.carinae.ai.agent.carinae.utils.CanonicalResultShape;
import com.carinae.ai.agent.carinae.utils.CanonicalWireFormat;
import com.carinae.ai.agent.carinae.utils.SafeTransforms;
import com.carinae.ai.agent.carinae.utils.nats.JetstreamPolicy.PublishReceipt;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.PublishAck;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "carinae.nats", name = "enabled", havingValue = "true")
public class NatsClientV2 {
    private static final Logger log = LoggerFactory.getLogger(NatsClientV2.class);

    private final ObjectMapper objectMapper;
    private final Connection connection;
    private final JetStream jetStream;
    private final JetStreamManagement jetStreamManagement;

    public NatsClientV2(
            ObjectMapper objectMapper,
            Connection connection,
            JetStream jetStream,
            JetStreamManagement jetStreamManagement) {
        this.objectMapper = objectMapper;
        this.connection = connection;
        this.jetStream = jetStream;
        this.jetStreamManagement = jetStreamManagement;
    }

    public boolean isConfigured() {
        return true;
    }

    public boolean isAvailable() {
        return connection.getStatus() == Connection.Status.CONNECTED;
    }

    public JetStream jetStream() {
        return jetStream;
    }

    public JetStreamManagement jetStreamManagement() {
        return jetStreamManagement;
    }

    public CanonicalResultShape<PublishReceipt> publish(CanonicalWireFormat wireFormat) {
        try {
            Message outbound = requireWireFormat(wireFormat).toNatsMessage(objectMapper);
            PublishAck ack = jetStream.publish(outbound);
            return CanonicalResultShape.success(
                    PublishReceipt.from(ack),
                    transportMetadata("operation", "publish", "subject", wireFormat.subject()));
        } catch (Exception e) {
            String subject = wireFormat == null ? "unknown" : wireFormat.subject();
            log.warn("Failed to publish JetStream message to subject {}: {}", subject, e.getMessage());
            return CanonicalResultShape.failure(
                    new DomainError(
                            "Failed to publish JetStream message",
                            Set.of(ErrorKind.DEPENDENCY_FAILURE),
                            Map.of("subject", subject, "cause", e.getMessage())),
                    transportMetadata("operation", "publish", "subject", subject));
        }
    }

    public CompletableFuture<CanonicalResultShape<PublishReceipt>> publishAsync(CanonicalWireFormat wireFormat) {
        try {
            Message outbound = requireWireFormat(wireFormat).toNatsMessage(objectMapper);
            return jetStream.publishAsync(outbound)
                    .handle((ack, throwable) -> throwable == null
                            ? CanonicalResultShape.success(
                                    PublishReceipt.from(ack),
                                    transportMetadata("operation", "publishAsync", "subject", wireFormat.subject()))
                            : CanonicalResultShape.failure(
                                    new DomainError(
                                            "Failed to publish JetStream message",
                                            Set.of(ErrorKind.DEPENDENCY_FAILURE),
                                            Map.of(
                                                    "subject", wireFormat.subject(),
                                                    "cause", throwable.getMessage())),
                                    transportMetadata("operation", "publishAsync", "subject", wireFormat.subject())));
        } catch (Exception e) {
            String subject = wireFormat == null ? "unknown" : wireFormat.subject();
            return CompletableFuture.completedFuture(CanonicalResultShape.failure(
                    new DomainError(
                            "Failed to publish JetStream message",
                            Set.of(ErrorKind.DEPENDENCY_FAILURE),
                            Map.of("subject", subject, "cause", e.getMessage())),
                    transportMetadata("operation", "publishAsync", "subject", subject)));
        }
    }

    public CanonicalResultShape<JetStreamSubscription> subscribe(
            String subject,
            PushSubscribeOptions options) {
        try {
            return CanonicalResultShape.success(
                    jetStream.subscribe(
                            normalizeToken(subject, "unknown"),
                            options == null ? PushSubscribeOptions.DEFAULT_PUSH_OPTS : options),
                    transportMetadata("operation", "subscribe", "subject", subject));
        } catch (Exception e) {
            log.warn("Failed to subscribe JetStream subject {}: {}", subject, e.getMessage());
            return CanonicalResultShape.failure(
                    new DomainError(
                            "Failed to subscribe JetStream subject",
                            Set.of(ErrorKind.DEPENDENCY_FAILURE),
                            Map.of("subject", normalizeToken(subject, "unknown"), "cause", e.getMessage())),
                    transportMetadata("operation", "subscribe", "subject", subject));
        }
    }

    public CanonicalResultShape<JetStreamSubscription> subscribePull(
            String subject,
            PullSubscribeOptions options) {
        try {
            return CanonicalResultShape.success(
                    jetStream.subscribe(
                            normalizeToken(subject, "unknown"),
                            options == null ? PullSubscribeOptions.DEFAULT_PULL_OPTS : options),
                    transportMetadata("operation", "subscribePull", "subject", subject));
        } catch (Exception e) {
            log.warn("Failed to subscribe JetStream pull subject {}: {}", subject, e.getMessage());
            return CanonicalResultShape.failure(
                    new DomainError(
                            "Failed to subscribe JetStream pull subject",
                            Set.of(ErrorKind.DEPENDENCY_FAILURE),
                            Map.of("subject", normalizeToken(subject, "unknown"), "cause", e.getMessage())),
                    transportMetadata("operation", "subscribePull", "subject", subject));
        }
    }

    private CanonicalWireFormat requireWireFormat(CanonicalWireFormat wireFormat) {
        if (wireFormat == null) {
            throw new IllegalArgumentException("CanonicalWireFormat is required");
        }
        return wireFormat;
    }

    private Map<String, Object> transportMetadata(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (key1 != null && value1 != null) {
            metadata.put(key1, value1);
        }
        if (key2 != null && value2 != null) {
            metadata.put(key2, value2);
        }
        return Map.copyOf(metadata);
    }

    private String normalizeToken(String token, String fallback) {
        return SafeTransforms.extractIfValidOrElse(
                token,
                candidate -> candidate != null,
                String::trim,
                candidate -> candidate != null && !candidate.isBlank(),
                fallback);
    }

    @PreDestroy
    public void close() {
        // Connection lifecycle is owned by NatsTransport.
    }
}
