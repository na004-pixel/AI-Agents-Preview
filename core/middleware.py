from typing import Any

class CanonicalResultShape:
    @staticmethod
    def success(data, metadata=None):
        return {"success": True, "data": data, "metadata": metadata or {}}
    
    @staticmethod
    def failure(error, metadata=None):
        return {"success": False, "error": error, "metadata": metadata or {}}

class DomainError(Exception):
    def __init__(self, message, error_kind, details):
        super().__init__(message)
        self.message = message
        self.error_kind = error_kind
        self.details = details

from core.loop import LLMLoop, ForkJobResult

class LLMJobMiddleware:
    CLIENT_ID_PREFIX = "job:"

    def __init__(self, runtime: Any, config: Any, genaiClient: Any, transcriptPersistence: Any):
        self.runtime = runtime
        self.config = config
        self.genaiClient = genaiClient
        self.transcriptPersistence = transcriptPersistence

    def execute(self, jobRecord: Any):
        if jobRecord is None or not jobRecord.payload():
            return CanonicalResultShape.failure(
                DomainError("LLM job is required", {"VALIDATION"}, {})
            )

        job = jobRecord.payload()
        clientIdentity = self.CLIENT_ID_PREFIX + jobRecord.jobId()

        try:
            effectiveJob = job
            self.registerPermissions(clientIdentity, effectiveJob)

            genaiConfig = self.enrichConfig(effectiveJob)
            contents = self.buildContents(job)

            loop = LLMLoop(self.runtime, self.config)
            future = loop.execute(
                jobRecord,
                jobRecord.jobId(),
                effectiveJob.modelName(),
                genaiConfig,
                contents,
                self.genaiClient,
                effectiveJob.optInFields(),
                clientIdentity,
                self.transcriptPersistence
            )
            loopResult = future.result()

            if isinstance(loopResult, ForkJobResult.Success):
                return CanonicalResultShape.success(loopResult.data, {"jobId": jobRecord.jobId()})

            return CanonicalResultShape.failure(
                loopResult.error, {"jobId": jobRecord.jobId()}
            )
        except Exception as e:
            return CanonicalResultShape.failure(
                DomainError("Failed to execute LLM job", {"INTERNAL"}, {"cause": str(e)})
            )

    def enrichConfig(self, job):
        baseConfig = job.genaiConfig().toGenerateContentConfig()
        return baseConfig

    def buildContents(self, job):
        class Part:
            def __init__(self, t):
                self.text_val = t
            @staticmethod
            def fromText(t):
                return Part(t)
                
        class ContentBuilder:
            def __init__(self):
                self._role = None
                self._parts = None
            def role(self, r):
                self._role = r
                return self
            def parts(self, p):
                self._parts = p
                return self
            def build(self):
                class Content:
                    def __init__(self, r, p):
                        self.role = r
                        self.parts = p
                return Content(self._role, self._parts)

        parts = []
        prompt_text = job.prompt().strip()
        parts.append(Part.fromText(prompt_text))

        if not parts:
            return [ContentBuilder().role("user").parts([Part.fromText("")]).build()]

        return [ContentBuilder().role("user").parts(parts).build()]

    def registerPermissions(self, clientIdentity, job):
        pass
