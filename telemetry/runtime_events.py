import logging
from typing import Dict, Any, Optional

logger = logging.getLogger(__name__)

class CanonicalWireFormat:
    def __init__(self, subject: str, headers: Dict[str, str], data: Dict[str, Any]):
        self.subject_val = subject
        self.headers_val = headers
        self.data_val = data
        
    def subject(self):
        return self.subject_val

class JobsRuntimeObservabilityProtocol:
    PROTOCOL = "jobs-runtime-observability"

    def __init__(self, natsClient: Optional[Any] = None):
        self.natsClient = natsClient

    def emit(self, eventType: str, component: str, metadata: Dict[str, str], body: Dict[str, Any]):
        if not eventType:
            return

        nodeId = "node-1" # [Simplified] Node ID resolution
        
        headers = {}
        headers["carinae-message-type"] = eventType
        headers["carinae-node-id"] = nodeId
        headers["carinae-protocol"] = self.PROTOCOL

        wireFormat = CanonicalWireFormat(
            "carinae.jobs.runtime." + nodeId,
            headers,
            {"data": body} if body is not None else {}
        )

        try:
            if self.natsClient:
                self.natsClient.publish(wireFormat)
        except Exception as e:
            logger.warning("Failed to emit jobs runtime event")
