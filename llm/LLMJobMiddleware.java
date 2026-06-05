package com.carinae.ai.agent.carinae.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.carinae.ai.agent.carinae.CarinaeConfig;
import com.carinae.ai.agent.carinae.llm.types.LLMJob;
import com.carinae.ai.agent.carinae.llm.types.MissionResult;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.DomainError;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.ErrorKind;
import com.carinae.ai.agent.carinae.utils.CanonicalResultShape;
import com.carinae.ai.agent.carinae.utils.frameworks.forkJoin.types.ForkJobResult;
import com.carinae.ai.agent.carinae.utils.protocols.jobs.pub.types.CoreJobRecord;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;

@Component
public class LLMJobMiddleware {
    private static final String CLIENT_ID_PREFIX = "job:";

    private final AgentRuntime runtime;
    private final CarinaeConfig config;
    private final Client genaiClient;
    private final LLMTranscriptPersistence transcriptPersistence;

    public LLMJobMiddleware(
            AgentRuntime runtime,
            CarinaeConfig config,
            Client genaiClient,
            LLMTranscriptPersistence transcriptPersistence) {
        this.runtime = runtime;
        this.config = config;
        this.genaiClient = genaiClient;
        this.transcriptPersistence = transcriptPersistence;
    }

    public CanonicalResultShape<MissionResult> execute(CoreJobRecord<LLMJob> jobRecord) {
        if (jobRecord == null || jobRecord.payload() == null) {
            return CanonicalResultShape.failure(
                    new DomainError("LLM job is required", Set.of(ErrorKind.VALIDATION), Map.of()));
        }

        LLMJob job = jobRecord.payload();
        String clientIdentity = CLIENT_ID_PREFIX + jobRecord.jobId();

        try {
            // [Simplified] State loading and job reconciliation logic omitted for showcase
            LLMJob effectiveJob = job;
            
            registerPermissions(clientIdentity, effectiveJob);

            GenerateContentConfig genaiConfig = enrichConfig(effectiveJob);
            List<Content> contents = buildContents(job);

            LLMLoop loop = new LLMLoop(runtime, config);
            ForkJobResult<MissionResult> loopResult = loop.execute(
                    jobRecord,
                    jobRecord.jobId(),
                    effectiveJob.modelName(),
                    genaiConfig,
                    contents,
                    genaiClient,
                    effectiveJob.optInFields(),
                    clientIdentity,
                    transcriptPersistence).join();

            if (loopResult instanceof ForkJobResult.Success<MissionResult> success) {
                return CanonicalResultShape.success(success.data(), Map.of("jobId", jobRecord.jobId()));
            }

            return CanonicalResultShape.failure(
                    ((ForkJobResult.Failure<MissionResult>) loopResult).error(),
                    Map.of("jobId", jobRecord.jobId()));
        } catch (Exception e) {
            return CanonicalResultShape.failure(
                    new DomainError("Failed to execute LLM job", Set.of(ErrorKind.INTERNAL), Map.of("cause", e.getMessage())));
        }
    }

    public GenerateContentConfig enrichConfig(LLMJob job) {
        GenerateContentConfig baseConfig = job.genaiConfig().toGenerateContentConfig();
        // [Simplified] Tools and Thinking injection simplified
        return baseConfig;
    }

    public List<Content> buildContents(LLMJob job) {
        List<Part> parts = new ArrayList<>();
            parts.add(Part.fromText(job.prompt().trim()));
        }

        if (parts.isEmpty()) {
            return List.of(Content.builder().role("user").parts(List.of(Part.fromText(""))).build());
        }

        return List.of(Content.builder().role("user").parts(parts).build());
    }

    @Transactional
    public void registerPermissions(String clientIdentity, LLMJob job) {
        // [Simplified] Permission registration
    }
}
