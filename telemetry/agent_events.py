import logging
from typing import Optional

logger = logging.getLogger(__name__)

class CanonicalResultShape:
    class Failure:
        pass

class AgentObservabilityProtocol:
    def __init__(self, natsClient: Optional[Any] = None):
        self.natsClient = natsClient

    def emit(self, observation: Any):
        if not observation:
            return
            
        try:
            subject = self.buildSubject(observation)
            wireFormat = observation.toWireFormat(subject)

            result = self.natsClient.publish(wireFormat) if self.natsClient else None
            
            if isinstance(result, CanonicalResultShape.Failure):
                logger.warning(f"Failed to emit [{observation.observationType()}] for job {observation.jobId()}")
        except Exception as e:
            logger.warning(f"Failed to emit observation for job {observation.jobId()}: {str(e)}")

    def buildSubject(self, observation: Any) -> str:
        return "carinae.observations." + self.sanitize(observation.jobId())

    def sanitize(self, token: str) -> str:
        if token is None or not token.strip():
            return "unknown"
        return token.replace(' ', '_')
