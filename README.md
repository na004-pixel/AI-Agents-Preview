# AI Agent Backend - Code Sample

This is a limited public preview of an autonomous AI agent backend built with Python. It provides a brief insight into the actual code to demonstrate the core LLM execution loop, tool sandboxing, and telemetry integration.

## What it does

At a high level, this system:
1. Takes a user prompt and sets up a secure environment for the LLM.
2. Runs a multi-turn conversation loop where the LLM can decide to use tools.
3. Executes those tools safely (like reading or writing to a sandboxed file system).
4. Emits real-time telemetry events over NATS JetStream so the rest of the system knows what the agent is doing.

## File Breakdown

### 1. The Core Loop (`core/`)
* **`loop.py`**: The main execution engine. It manages the back-and-forth conversation with the model, handles API rate limits with retries, and saves the conversation history at every turn.
* **`middleware.py`**: The setup phase. It takes an incoming job request, applies security permissions, and configures the LLM before passing it to the loop.

### 2. Tools (`tools/`)
* **`read_file.py` & `write_file.py`**: Tools the LLM can call to interact with files. They include defensive checks to prevent the AI from accessing files outside its allowed directory (path traversal protection) and truncate files that are too large.

### 3. API Integration (`api/`)
* **`genai.py`**: A custom REST client for the Google Vertex AI API. It handles raw JSON payloads and OAuth token generation directly, bypassing the standard SDK for more granular control over the model.

### 4. Telemetry (Observability) (`telemetry/`)
* **`nats_client.py`**: A wrapper for NATS JetStream, used for fast, asynchronous messaging.
* **`agent_events.py` & `runtime_events.py`**: These classes fire off lightweight events (like "job started" or "tool executed") over NATS. This allows the agent's progress to be monitored externally without slowing down the main execution thread.
