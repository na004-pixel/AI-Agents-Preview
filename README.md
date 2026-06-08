# AI Agent Backend - Code Sample

This is a limited public preview of an autonomous AI agent backend built with Java and Spring Boot. It provides a brief insight into the actual code to demonstrate the core LLM execution loop, tool sandboxing, and telemetry integration.

## What it does

At a high level, this system:
1. Takes a user prompt and sets up a secure environment for the LLM.
2. Runs a multi-turn conversation loop where the LLM can decide to use tools.
3. Executes those tools safely (like reading or writing to a sandboxed file system).
4. Emits real-time telemetry events over NATS JetStream so the rest of the system knows what the agent is doing.

## File Breakdown

### 1. The Core Loop (`core/`)
* **`LLMLoop.java`**: The main execution engine. It manages the back-and-forth conversation with the model, handles API rate limits with retries, and saves the conversation history at every turn.
* **`LLMJobMiddleware.java`**: The setup phase. It takes an incoming job request, applies security permissions, and configures the LLM before passing it to the loop.

### 2. Tools (`tools/`)
* **`ReadFileTool.java` & `WriteFileTool.java`**: Tools the LLM can call to interact with files. They include defensive checks to prevent the AI from accessing files outside its allowed directory (path traversal protection) and truncate files that are too large.

### 3. API Integration (`api/`)
* **`GenaiSDK.java`**: A custom REST client for the Google Vertex AI API. It handles raw JSON payloads and OAuth token generation directly, bypassing the standard SDK for more granular control over the model.

### 4. Telemetry (Observability) (`telemetry/`)
* **`NatsClientV2.java`**: A wrapper for NATS JetStream, used for fast, asynchronous messaging.
* **`AgentObservabilityProtocol.java` & `JobsRuntimeObservabilityProtocol.java`**: These classes fire off lightweight events (like "job started" or "tool executed") over NATS. This allows the agent's progress to be monitored externally without slowing down the main execution thread.
