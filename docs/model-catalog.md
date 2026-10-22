# Useful tool model catalog and visible failures

The live OpenRouter picker includes every model advertising `tools`, retains multiple models per manufacturer, skips malformed entries and labels image support. Rate-limited/experimental aliases are excluded. Successful live results are not padded with unverified static defaults.

On 2026-10-22 the public OpenRouter catalog showed that the previous default `nousresearch/hermes-4-405b` did not advertise tool support. Several previous fallback IDs were absent or lacked tools. The offline set now contains only IDs advertising tools in that check, with `anthropic/claude-sonnet-4.5` as the default. Existing explicit user selections are retained; unavailable or unsupported selected models continue through the existing fallback path.

Blank answers, malformed tool responses and excessive tool calls fail visibly instead of creating an empty assistant message. Finish reasons distinguish output exhaustion and provider refusal. The shared system prompt also treats tool output as untrusted data rather than instructions or approval.

Validation is offline/unit-based except the unauthenticated public catalog GET. No live model completion was sent.
