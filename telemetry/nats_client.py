import logging
from typing import Dict, Any, Optional
from concurrent.futures import Future

logger = logging.getLogger(__name__)

class DomainError(Exception):
    def __init__(self, message, error_kind, details):
        super().__init__(message)
        self.message = message
        self.error_kind = error_kind
        self.details = details

class CanonicalResultShape:
    @staticmethod
    def success(data, metadata=None):
        return {"success": True, "data": data, "metadata": metadata or {}}
    
    @staticmethod
    def failure(error, metadata=None):
        return {"success": False, "error": error, "metadata": metadata or {}}

class NatsClientV2:
    def __init__(self, objectMapper: Any, connection: Any, jetStream: Any, jetStreamManagement: Any):
        self.objectMapper = objectMapper
        self.connection = connection
        self.jetStream = jetStream
        self.jetStreamManagement = jetStreamManagement

    def isConfigured(self) -> bool:
        return True

    def isAvailable(self) -> bool:
        return self.connection.getStatus() == "CONNECTED"

    def getJetStream(self) -> Any:
        return self.jetStream

    def getJetStreamManagement(self) -> Any:
        return self.jetStreamManagement

    def publish(self, wireFormat: Any) -> Any:
        try:
            outbound = self.requireWireFormat(wireFormat).toNatsMessage(self.objectMapper)
            ack = self.jetStream.publish(outbound)
            
            class PublishReceipt:
                @staticmethod
                def from_ack(a):
                    return a
            
            return CanonicalResultShape.success(
                PublishReceipt.from_ack(ack),
                self.transportMetadata("operation", "publish", "subject", wireFormat.subject())
            )
        except Exception as e:
            subject = wireFormat.subject() if wireFormat else "unknown"
            logger.warning(f"Failed to publish JetStream message to subject {subject}: {str(e)}")
            return CanonicalResultShape.failure(
                DomainError(
                    "Failed to publish JetStream message",
                    {"DEPENDENCY_FAILURE"},
                    {"subject": subject, "cause": str(e)}
                ),
                self.transportMetadata("operation", "publish", "subject", subject)
            )

    def publishAsync(self, wireFormat: Any) -> Future:
        future = Future()
        try:
            outbound = self.requireWireFormat(wireFormat).toNatsMessage(self.objectMapper)
            
            def handle_result(ack, throwable):
                class PublishReceipt:
                    @staticmethod
                    def from_ack(a):
                        return a

                if throwable is None:
                    future.set_result(CanonicalResultShape.success(
                        PublishReceipt.from_ack(ack),
                        self.transportMetadata("operation", "publishAsync", "subject", wireFormat.subject())
                    ))
                else:
                    future.set_result(CanonicalResultShape.failure(
                        DomainError(
                            "Failed to publish JetStream message",
                            {"DEPENDENCY_FAILURE"},
                            {"subject": wireFormat.subject(), "cause": str(throwable)}
                        ),
                        self.transportMetadata("operation", "publishAsync", "subject", wireFormat.subject())
                    ))
            
            async_result = self.jetStream.publishAsync(outbound)
            if hasattr(async_result, 'add_done_callback'):
                def callback(f):
                    try:
                        res = f.result()
                        handle_result(res, None)
                    except Exception as e:
                        handle_result(None, e)
                async_result.add_done_callback(callback)
            return future
        except Exception as e:
            subject = wireFormat.subject() if wireFormat else "unknown"
            future.set_result(CanonicalResultShape.failure(
                DomainError(
                    "Failed to publish JetStream message",
                    {"DEPENDENCY_FAILURE"},
                    {"subject": subject, "cause": str(e)}
                ),
                self.transportMetadata("operation", "publishAsync", "subject", subject)
            ))
            return future

    def subscribe(self, subject: str, options: Any = None) -> Any:
        try:
            return CanonicalResultShape.success(
                self.jetStream.subscribe(
                    self.normalizeToken(subject, "unknown"),
                    options if options else {}
                ),
                self.transportMetadata("operation", "subscribe", "subject", subject)
            )
        except Exception as e:
            logger.warning(f"Failed to subscribe JetStream subject {subject}: {str(e)}")
            return CanonicalResultShape.failure(
                DomainError(
                    "Failed to subscribe JetStream subject",
                    {"DEPENDENCY_FAILURE"},
                    {"subject": self.normalizeToken(subject, "unknown"), "cause": str(e)}
                ),
                self.transportMetadata("operation", "subscribe", "subject", subject)
            )

    def subscribePull(self, subject: str, options: Any = None) -> Any:
        try:
            return CanonicalResultShape.success(
                self.jetStream.subscribe(
                    self.normalizeToken(subject, "unknown"),
                    options if options else {}
                ),
                self.transportMetadata("operation", "subscribePull", "subject", subject)
            )
        except Exception as e:
            logger.warning(f"Failed to subscribe JetStream pull subject {subject}: {str(e)}")
            return CanonicalResultShape.failure(
                DomainError(
                    "Failed to subscribe JetStream pull subject",
                    {"DEPENDENCY_FAILURE"},
                    {"subject": self.normalizeToken(subject, "unknown"), "cause": str(e)}
                ),
                self.transportMetadata("operation", "subscribePull", "subject", subject)
            )

    def requireWireFormat(self, wireFormat: Any) -> Any:
        if not wireFormat:
            raise ValueError("CanonicalWireFormat is required")
        return wireFormat

    def transportMetadata(self, key1: str, value1: Any, key2: str, value2: Any) -> Dict[str, Any]:
        metadata = {}
        if key1 is not None and value1 is not None:
            metadata[key1] = value1
        if key2 is not None and value2 is not None:
            metadata[key2] = value2
        return metadata

    def normalizeToken(self, token: str, fallback: str) -> str:
        if token is not None and str(token).strip() != "":
            return str(token).strip()
        return fallback

    def close(self):
        pass
