# Agent Loop Demo Rules

This directory is a learner-owned Java Maven demo, not a curriculum lesson.

- Keep chat messages, requests, responses, tool definitions, and tool arguments as named Java types. Do not use `List<Map<String, Object>>` or untyped JSON objects as application state.
- Use Jackson only at HTTP/JSON boundaries. Keep the agent loop independent of HTTP details.
- Keep every tool behind one interface with a typed argument record and an explicit definition.
- Never hard-code or log API keys. Read `DEEPSEEK_API_KEY` and `EXA_API_KEY` from the environment.
- Treat model tool calls and search results as untrusted data. Ask the user before running every Bash command and again before sending its output to DeepSeek. Cap command runtime and captured output.
- Bound model turns and surface API, stream, and tool errors clearly. Do not claim success after an incomplete stream.
- Build with `mvn compile`. When credentials are available, smoke test a real DeepSeek tool call. Search remains unverified until `EXA_API_KEY` is configured.
- Keep code compatible with Java 17 even if the local JDK is newer.
