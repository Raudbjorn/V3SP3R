# Native Anthropic provider

This feature builds on provider-aware settings. Anthropic uses `/v1/messages`, `x-api-key` and `anthropic-version: 2023-06-01`. It does not use the OpenAI-compatible endpoint or Bearer authentication.

The adapter translates the shared system prompt, tool schemas, assistant tool calls, user tool results and embedded images into native Messages blocks. Text, tool calls and token usage return through the existing command validator and approval flow. Parallel tool use is disabled to match the existing one-command-per-turn executor contract; an excessive response fails visibly.

Provider selection, model and credentials are read once per request. No application-global collection or initial-provider fallback race is introduced. Native model-catalog requests use the same provider authentication.

Validation uses MockWebServer to inspect actual HTTP requests and deterministic response/image fixtures. Live Anthropic credentials are not available; no live completion or billing request was made.
