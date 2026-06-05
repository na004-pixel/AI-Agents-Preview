package com.carinae.ai.agent.carinae.llm;

import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;

import com.google.genai.Client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.carinae.ai.agent.carinae.CarinaeConfig;
import com.carinae.ai.agent.carinae.llm.types.MissionResult;
import com.carinae.ai.agent.carinae.llm.types.MissionResult.Diagnostics;
import com.carinae.ai.agent.carinae.llm.types.FunctionCallRecord;
import com.carinae.ai.agent.carinae.llm.sdk.GenaiSDK.Usage;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.DomainError;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.ErrorKind;
import com.carinae.ai.agent.carinae.tools.types.toolTransport.ToolResult;
import com.carinae.ai.agent.carinae.utils.frameworks.forkJoin.types.ForkJobResult;

public class LLMLoop {
    private static final tools.jackson.databind.ObjectMapper OBJECT_MAPPER = new tools.jackson.databind.ObjectMapper();

    private final CarinaeConfig config;
    private final AgentRuntime runtime;

    public LLMLoop(AgentRuntime runtime, CarinaeConfig config) {
        this.config = config;
        this.runtime = runtime;
    }

    public CompletableFuture<ForkJobResult<MissionResult>> execute(
            com.carinae.ai.agent.carinae.utils.protocols.jobs.pub.types.CoreJobRecord<com.carinae.ai.agent.carinae.llm.types.LLMJob> jobRecord,
            String requestId,
            String modelName,
            GenerateContentConfig genaiConfig,
            List<Content> contents,
            Client genaiClient,
            Map<String, Boolean> optInFields,
            String clientIdentity,
            LLMTranscriptPersistence transcriptPersistence) {

        long startedTimestamp = System.currentTimeMillis();
        AgentHarness core = new AgentHarness(runtime, config, transcriptPersistence);
        GenerateContentConfig currentConfig = core.applyModelFamilyConfig(modelName, genaiConfig, optInFields);

        AgentHarness.TurnState state = new AgentHarness.TurnState(
                new ArrayList<>(contents),
                new ArrayList<>(contents),
                new ArrayList<>(),
                currentConfig,
                null,
                new Usage(0, 0, 0, 0),
                new ArrayList<>(),
                "",
                0,
                false);

        int turn = 0;
        boolean cancelled = false;
        boolean maxTurnsReached = false;

        String serviceAccountJson = null;
        try {
            serviceAccountJson = Files.readString(Path.of(config.geminiAi().serviceAccountJsonPath()));
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }

        try {
            int initialTurn = 0;
            if (jobRecord.metadata().containsKey("currentTurn")) {
                try {
                    initialTurn = Integer.parseInt(jobRecord.metadata().get("currentTurn"));
                } catch (Exception ignored) {
                }
            }

            for (turn = initialTurn; turn < initialTurn + config.agents().maxTurns(); turn++) {
                AgentHarness.TurnOutcome outcome = core.executeTurn(
                        new AgentHarness.TurnRequest(
                                jobRecord,
                                requestId,
                                modelName,
                                genaiConfig,
                                genaiClient,
                                optInFields,
                                clientIdentity,
                                serviceAccountJson,
                                turn,
                                state));
                state = outcome.state();

                if (outcome.error() != null && outcome.retryTurn()) {
                    long retryDelayMs = config.agents().retryInterval() != null ? config.agents().retryInterval().toMillis() : 60000;
                    System.err.println("\n[RATE LIMIT] 429 Too Many Requests detected. Waiting " + (retryDelayMs / 1000) + "s before retry...");
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        transcriptPersistence.persistSnapshotWithRetry(
                                jobRecord, genaiConfig, state.conversationHistory(), state.functionCallRecords(), state.tokenCountLog(), turn, state.lastText(), "failed", null, outcome.error(), state.totalUsage());
                        return CompletableFuture.completedFuture(new ForkJobResult.Failure<>(outcome.error()));
                    }
                    turn--;
                    continue;
                }

                if (outcome.error() != null) {
                    transcriptPersistence.persistSnapshotWithRetry(
                            jobRecord, genaiConfig, state.conversationHistory(), state.functionCallRecords(), state.tokenCountLog(), turn, state.lastText(), "failed", null, outcome.error(), state.totalUsage());
                    return CompletableFuture.completedFuture(new ForkJobResult.Failure<>(outcome.error()));
                }

                if (outcome.shouldBreak()) {
                    break;
                }
            }

            List<Content> conversationHistory = state.conversationHistory();
            List<FunctionCallRecord> functionCallRecords = state.functionCallRecords();
            Usage totalUsage = state.totalUsage();
            List<Map<String, Object>> tokenCountLog = state.tokenCountLog();
            String lastText = state.lastText();
            cancelled = state.cancelled();

            if (turn >= config.agents().maxTurns()) {
                maxTurnsReached = true;
            }

            long elapsedMs = System.currentTimeMillis() - startedTimestamp;

            // Build result
            return CompletableFuture.completedFuture(buildResult(
                    modelName,
                    functionCallRecords,
                    totalUsage,
                    tokenCountLog,
                    conversationHistory,
                    cancelled,
                    maxTurnsReached,
                    optInFields,
                    elapsedMs,
                    lastText,
                    jobRecord,
                    genaiConfig,
                    transcriptPersistence,
                    turn));
        }
        catch (Exception e) {
            List<Content> conversationHistory = state.conversationHistory();
            List<FunctionCallRecord> functionCallRecords = state.functionCallRecords();
            List<Map<String, Object>> tokenCountLog = state.tokenCountLog();
            String lastText = state.lastText();
            Usage totalUsage = state.totalUsage();
            DomainError error = new DomainError(
                    "LLM loop failed",
                    Set.of(ErrorKind.INTERNAL),
                    Map.of("cause", e.getMessage()));
            transcriptPersistence.persistSnapshotWithRetry(
                    jobRecord, genaiConfig, conversationHistory, functionCallRecords, tokenCountLog, turn, lastText, "failed", null, error, totalUsage);
            return CompletableFuture.completedFuture(new ForkJobResult.Failure<>(error));
        }

        finally {
            core.cleanupCache(state.activeCacheName(), genaiClient, serviceAccountJson);
        }
    }


    private ForkJobResult<MissionResult> buildResult(
            String modelName,
            List<FunctionCallRecord> functionCallRecords,
            Usage usage,
            List<Map<String, Object>> tokenCountLog,
            List<Content> conversationHistory,
            boolean cancelled,
            boolean maxTurnsReached,
            Map<String, Boolean> optInFields,
            long elapsedMs,
            String lastText,
            com.carinae.ai.agent.carinae.utils.protocols.jobs.pub.types.CoreJobRecord<com.carinae.ai.agent.carinae.llm.types.LLMJob> jobRecord,
            GenerateContentConfig genaiConfig,
            LLMTranscriptPersistence transcriptPersistence,
            Integer turn) {

        Map<String, String> extras = new HashMap<>();
        putOptInField(extras, optInFields, "functionCallTraces", () -> functionCallRecords);
        putOptInField(extras, optInFields, "conversationHistory", () -> conversationHistory);
        extras.put("token_counts_log", serializeOptInValue(tokenCountLog));

        extras.put("turns", String.valueOf(tokenCountLog.size()));
        extras.put("message_count", String.valueOf(conversationHistory.size()));
        extras.put("function_calls", String.valueOf(functionCallRecords.size()));
        extras.put("input_tokens", String.valueOf(usage.inputTokens()));
        extras.put("output_tokens", String.valueOf(usage.outputTokens()));
        extras.put("total_tokens", String.valueOf(usage.totalTokens()));
        extras.put("reasoning_tokens", String.valueOf(usage.reasoningTokens()));

        Diagnostics diagnostics = new Diagnostics(elapsedMs, usage, modelName, extras);

        if (cancelled) {
            MissionResult missionResult = new MissionResult(
                    false,
                    "subagent-cancelled",
                    diagnostics,
                    Optional.of(new DomainError(
                            "subagent-cancelled",
                            Set.of(ErrorKind.CANCELLED),
                            Map.of())));
            transcriptPersistence.persistSnapshotWithRetry(
                    jobRecord, genaiConfig, conversationHistory, functionCallRecords, tokenCountLog, turn, lastText, "cancelled", missionResult, null, usage);
            return new ForkJobResult.Success<>(missionResult);
        }

        if (maxTurnsReached) {
            MissionResult missionResult = new MissionResult(
                    false,
                    "max-turns-reached",
                    diagnostics,
                    Optional.of(new DomainError(
                            "max-turns-reached",
                            Set.of(ErrorKind.FORBIDDEN),
                            Map.of())));
            transcriptPersistence.persistSnapshotWithRetry(
                    jobRecord, genaiConfig, conversationHistory, functionCallRecords, tokenCountLog, turn, lastText, "max-turns-reached", missionResult, null, usage);
            return new ForkJobResult.Success<>(missionResult);
        }

        if (isFailure(functionCallRecords)) {
            MissionResult missionResult = new MissionResult(
                    false,
                    "tool-calls-failed",
                    diagnostics,
                    Optional.of(new DomainError(
                            "tool-calls-failed",
                            Set.of(ErrorKind.INTERNAL),
                            Map.of())));
            transcriptPersistence.persistSnapshotWithRetry(
                    jobRecord, genaiConfig, conversationHistory, functionCallRecords, tokenCountLog, turn, lastText, "tool-calls-failed", missionResult, null, usage);
            return new ForkJobResult.Success<>(missionResult);
        }

        MissionResult missionResult = new MissionResult(
                true,
                lastText,
                diagnostics,
                Optional.empty());
        transcriptPersistence.persistSnapshotWithRetry(
                jobRecord, genaiConfig, conversationHistory, functionCallRecords, tokenCountLog, turn, lastText, "completed", missionResult, null, usage);
        return new ForkJobResult.Success<>(missionResult);
    }

    private boolean isFailure(List<FunctionCallRecord> functionCallRecords) {
        if (functionCallRecords.isEmpty()) {
            return false;
        }

        double threshold = 0.5;
        long failedCalls = functionCallRecords.stream()
                .filter(functionCallRecord -> !ToolResult.wasToolExecutionSuccessful(functionCallRecord.resultsJson()))
                .count();
        double failureFraction = (double) failedCalls / functionCallRecords.size();
        return failureFraction >= threshold;
    }

    private void putOptInField(
            Map<String, String> extras,
            Map<String, Boolean> optInFields,
            String fieldName,
            Supplier<Object> valueSupplier) {
        if (!Boolean.TRUE.equals(optInFields.get(fieldName))) {
            return;
        }
        extras.put(fieldName, serializeOptInValue(valueSupplier.get()));
    }

    private String serializeOptInValue(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize opt-in field", e);
        }
    }

}
