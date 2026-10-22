# Provider streaming and context

Chat now consumes Server-Sent Events for OpenAI-compatible providers and Anthropic. Text is visible as it arrives. Tool argument fragments remain inert until the provider sends its terminal event and the normal command parser validates the assembled response. A closed or failed stream reports an error instead of executing a partial tool call. Cancellation closes the HTTP call, including a stalled body read.

For Anthropic, the system block and final tool definition carry ephemeral prompt-cache markers. Extended thinking is optional in Settings and reserves 2,048 tokens plus room for the response. Effort is exposed for Claude Opus 4.5 only; unknown models do not receive speculative effort parameters.

Native assistant content blocks (including signed thinking and compaction data) are stored in existing message metadata and replayed unchanged. Context trimming starts at a user turn and retains the latest compaction anchor. This deliberately treats the message count as a soft limit when a complete tool round or compaction anchor requires more history. It does not invent a summary or enable a speculative server-side compaction API.

Mock HTTP and deterministic SSE fixtures cover protocol encoding, partial tools, premature EOF, stream errors, cancellation, persistence, cache markers, and context boundaries. Live provider credentials and on-device audio playback are not available in this environment; those remain deployment acceptance checks.
