import time
import json
from concurrent.futures import Future

class DomainError(Exception):
    def __init__(self, message, error_kind, details):
        super().__init__(message)
        self.message = message
        self.error_kind = error_kind
        self.details = details

class ForkJobResult:
    class Success:
        def __init__(self, data):
            self.data = data
    class Failure:
        def __init__(self, error):
            self.error = error

class MissionResult:
    class Diagnostics:
        def __init__(self, elapsedMs, usage, modelName, extras):
            self.elapsedMs = elapsedMs
            self.usage = usage
            self.modelName = modelName
            self.extras = extras

    def __init__(self, success, lastText, diagnostics, error=None):
        self.success = success
        self.lastText = lastText
        self.diagnostics = diagnostics
        self.error = error

class LLMLoop:
    def __init__(self, runtime, config):
        self.runtime = runtime
        self.config = config

    def execute(self, jobRecord, requestId, modelName, genaiConfig, contents, genaiClient, optInFields, clientIdentity, transcriptPersistence) -> Future:
        startedTimestamp = int(time.time() * 1000)
        
        core = self.AgentHarnessMock(self.runtime, self.config, transcriptPersistence)
        currentConfig = core.applyModelFamilyConfig(modelName, genaiConfig, optInFields)
        
        class Usage:
            def __init__(self, i, o, t, r):
                self.inputTokens = lambda: i
                self.outputTokens = lambda: o
                self.totalTokens = lambda: t
                self.reasoningTokens = lambda: r

        state = self.TurnStateMock(
            list(contents),
            list(contents),
            [],
            currentConfig,
            None,
            Usage(0, 0, 0, 0),
            [],
            "",
            0,
            False
        )

        cancelled = False
        maxTurnsReached = False
        
        serviceAccountJson = None
        try:
            with open(self.config.geminiAi().serviceAccountJsonPath(), 'r') as f:
                serviceAccountJson = f.read()
        except IOError:
            pass

        turn = 0
        try:
            initialTurn = 0
            if "currentTurn" in jobRecord.metadata():
                try:
                    initialTurn = int(jobRecord.metadata()["currentTurn"])
                except Exception:
                    pass

            for turn in range(initialTurn, initialTurn + self.config.agents().maxTurns()):
                outcome = core.executeTurn(
                    self.TurnRequestMock(
                        jobRecord, requestId, modelName, genaiConfig, genaiClient, optInFields, clientIdentity, serviceAccountJson, turn, state
                    )
                )
                state = outcome.state()

                if outcome.error() is not None and outcome.retryTurn():
                    retryDelayMs = self.config.agents().retryInterval().toMillis() if self.config.agents().retryInterval() else 60000
                    print(f"\n[RATE LIMIT] 429 Too Many Requests detected. Waiting {retryDelayMs / 1000}s before retry...")
                    try:
                        time.sleep(retryDelayMs / 1000.0)
                    except InterruptedError:
                        transcriptPersistence.persistSnapshotWithRetry(
                            jobRecord, genaiConfig, state.conversationHistory(), state.functionCallRecords(), state.tokenCountLog(), turn, state.lastText(), "failed", None, outcome.error(), state.totalUsage()
                        )
                        future = Future()
                        future.set_result(ForkJobResult.Failure(outcome.error()))
                        return future
                    turn -= 1
                    continue

                if outcome.error() is not None:
                    transcriptPersistence.persistSnapshotWithRetry(
                        jobRecord, genaiConfig, state.conversationHistory(), state.functionCallRecords(), state.tokenCountLog(), turn, state.lastText(), "failed", None, outcome.error(), state.totalUsage()
                    )
                    future = Future()
                    future.set_result(ForkJobResult.Failure(outcome.error()))
                    return future

                if outcome.shouldBreak():
                    break

            conversationHistory = state.conversationHistory()
            functionCallRecords = state.functionCallRecords()
            totalUsage = state.totalUsage()
            tokenCountLog = state.tokenCountLog()
            lastText = state.lastText()
            cancelled = state.cancelled()

            if turn >= self.config.agents().maxTurns():
                maxTurnsReached = True

            elapsedMs = int(time.time() * 1000) - startedTimestamp

            future = Future()
            future.set_result(self.buildResult(
                modelName, functionCallRecords, totalUsage, tokenCountLog, conversationHistory, cancelled, maxTurnsReached, optInFields, elapsedMs, lastText, jobRecord, genaiConfig, transcriptPersistence, turn
            ))
            return future

        except Exception as e:
            conversationHistory = state.conversationHistory()
            functionCallRecords = state.functionCallRecords()
            tokenCountLog = state.tokenCountLog()
            lastText = state.lastText()
            totalUsage = state.totalUsage()
            
            error = DomainError("LLM loop failed", {"INTERNAL"}, {"cause": str(e)})
            transcriptPersistence.persistSnapshotWithRetry(
                jobRecord, genaiConfig, conversationHistory, functionCallRecords, tokenCountLog, turn, lastText, "failed", None, error, totalUsage
            )
            future = Future()
            future.set_result(ForkJobResult.Failure(error))
            return future

        finally:
            core.cleanupCache(state.activeCacheName(), genaiClient, serviceAccountJson)


    def buildResult(self, modelName, functionCallRecords, usage, tokenCountLog, conversationHistory, cancelled, maxTurnsReached, optInFields, elapsedMs, lastText, jobRecord, genaiConfig, transcriptPersistence, turn):
        extras = {}
        self.putOptInField(extras, optInFields, "functionCallTraces", lambda: functionCallRecords)
        self.putOptInField(extras, optInFields, "conversationHistory", lambda: conversationHistory)
        extras["token_counts_log"] = self.serializeOptInValue(tokenCountLog)

        extras["turns"] = str(len(tokenCountLog))
        extras["message_count"] = str(len(conversationHistory))
        extras["function_calls"] = str(len(functionCallRecords))
        extras["input_tokens"] = str(usage.inputTokens())
        extras["output_tokens"] = str(usage.outputTokens())
        extras["total_tokens"] = str(usage.totalTokens())
        extras["reasoning_tokens"] = str(usage.reasoningTokens())

        diagnostics = MissionResult.Diagnostics(elapsedMs, usage, modelName, extras)

        if cancelled:
            missionResult = MissionResult(
                False, "subagent-cancelled", diagnostics,
                DomainError("subagent-cancelled", {"CANCELLED"}, {})
            )
            transcriptPersistence.persistSnapshotWithRetry(
                jobRecord, genaiConfig, conversationHistory, functionCallRecords, tokenCountLog, turn, lastText, "cancelled", missionResult, None, usage
            )
            return ForkJobResult.Success(missionResult)

        if maxTurnsReached:
            missionResult = MissionResult(
                False, "max-turns-reached", diagnostics,
                DomainError("max-turns-reached", {"FORBIDDEN"}, {})
            )
            transcriptPersistence.persistSnapshotWithRetry(
                jobRecord, genaiConfig, conversationHistory, functionCallRecords, tokenCountLog, turn, lastText, "max-turns-reached", missionResult, None, usage
            )
            return ForkJobResult.Success(missionResult)

        if self.isFailure(functionCallRecords):
            missionResult = MissionResult(
                False, "tool-calls-failed", diagnostics,
                DomainError("tool-calls-failed", {"INTERNAL"}, {})
            )
            transcriptPersistence.persistSnapshotWithRetry(
                jobRecord, genaiConfig, conversationHistory, functionCallRecords, tokenCountLog, turn, lastText, "tool-calls-failed", missionResult, None, usage
            )
            return ForkJobResult.Success(missionResult)

        missionResult = MissionResult(True, lastText, diagnostics, None)
        transcriptPersistence.persistSnapshotWithRetry(
            jobRecord, genaiConfig, conversationHistory, functionCallRecords, tokenCountLog, turn, lastText, "completed", missionResult, None, usage
        )
        return ForkJobResult.Success(missionResult)

    def isFailure(self, functionCallRecords):
        if not functionCallRecords:
            return False
            
        threshold = 0.5
        failedCalls = sum(1 for rec in functionCallRecords if not self.ToolResultMock.wasToolExecutionSuccessful(rec.resultsJson()))
        failureFraction = float(failedCalls) / len(functionCallRecords)
        return failureFraction >= threshold

    def putOptInField(self, extras, optInFields, fieldName, valueSupplier):
        if optInFields.get(fieldName) is not True:
            return
        extras[fieldName] = self.serializeOptInValue(valueSupplier())

    def serializeOptInValue(self, value):
        try:
            return json.dumps(value, default=lambda o: o.__dict__)
        except Exception as e:
            raise RuntimeError("Failed to serialize opt-in field") from e

    # Mock classes to support the logic
    class AgentHarnessMock:
        def __init__(self, r, c, t): pass
        def applyModelFamilyConfig(self, m, g, o): return g
        def executeTurn(self, r): pass
        def cleanupCache(self, a, g, s): pass
        
    class TurnStateMock:
        def __init__(self, ch, ah, fcr, ac, anc, tu, tcl, lt, t, c):
            self._ch = ch
            self._fcr = fcr
            self._tu = tu
            self._tcl = tcl
            self._lt = lt
            self._c = c
            self._anc = anc
        def conversationHistory(self): return self._ch
        def functionCallRecords(self): return self._fcr
        def totalUsage(self): return self._tu
        def tokenCountLog(self): return self._tcl
        def lastText(self): return self._lt
        def cancelled(self): return self._c
        def activeCacheName(self): return self._anc

    class TurnRequestMock:
        def __init__(self, jr, ri, mn, gc, gcl, oif, ci, saj, t, s): pass

    class ToolResultMock:
        @staticmethod
        def wasToolExecutionSuccessful(res): return True
